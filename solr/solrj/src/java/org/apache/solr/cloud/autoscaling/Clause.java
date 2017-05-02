/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.autoscaling;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.apache.solr.common.MapWriter;
import org.apache.solr.common.util.Utils;
import org.apache.solr.cloud.autoscaling.Policy.ReplicaInfo;

import static java.util.Collections.reverseOrder;
import static java.util.Collections.singletonMap;
import static org.apache.solr.common.params.CoreAdminParams.COLLECTION;
import static org.apache.solr.common.params.CoreAdminParams.REPLICA;
import static org.apache.solr.common.params.CoreAdminParams.SHARD;
import static org.apache.solr.cloud.autoscaling.Clause.TestStatus.FAIL;
import static org.apache.solr.cloud.autoscaling.Clause.TestStatus.NOT_APPLICABLE;
import static org.apache.solr.cloud.autoscaling.Clause.TestStatus.PASS;
import static org.apache.solr.cloud.autoscaling.Operand.EQUAL;
import static org.apache.solr.cloud.autoscaling.Operand.GREATER_THAN;
import static org.apache.solr.cloud.autoscaling.Operand.LESS_THAN;
import static org.apache.solr.cloud.autoscaling.Operand.NOT_EQUAL;
import static org.apache.solr.cloud.autoscaling.Operand.WILDCARD;
import static org.apache.solr.cloud.autoscaling.Policy.ANY;
import static org.apache.solr.cloud.autoscaling.Policy.EACH;

// a set of conditions in a policy
public class Clause implements MapWriter, Comparable<Clause> {
  Map<String, Object> original;
  Condition collection, shard, replica, tag, globalTag;

  boolean strict = true;

  Clause(Map<String, Object> m) {
    this.original = m;
    strict = Boolean.parseBoolean(String.valueOf(m.getOrDefault("strict", "true")));
    Optional<String> globalTagName = m.keySet().stream().filter(Policy.GLOBAL_ONLY_TAGS::contains).findFirst();
    if (globalTagName.isPresent()) {
      globalTag = parse(globalTagName.get(), m);
      if (m.size() > 2) {
        throw new RuntimeException("Only one extra tag supported for the tag " + globalTagName.get() + " in " + Utils.toJSONString(m));
      }
      tag = parse(m.keySet().stream().filter(s -> (!globalTagName.get().equals(s) && !IGNORE_TAGS.contains(s))).findFirst().get(), m);
    } else {
      collection = parse(COLLECTION, m);
      shard = parse(SHARD, m);
      this.replica = parse(REPLICA, m);
      m.forEach((s, o) -> parseCondition(s, o));
    }
    if (tag == null)
      throw new RuntimeException("Invalid op, must have one and only one tag other than collection, shard,replica " + Utils.toJSONString(m));

  }

  public boolean doesOverride(Clause that) {
    return (collection.equals(that.collection) &&
        tag.name.equals(that.tag.name));

  }

  public boolean isPerCollectiontag() {
    return globalTag == null;
  }

  void parseCondition(String s, Object o) {
    if (IGNORE_TAGS.contains(s)) return;
    if (tag != null) {
      throw new IllegalArgumentException("Only one tag other than collection, shard, replica is possible");
    }
    tag = parse(s, singletonMap(s, o));
  }

  @Override
  public int compareTo(Clause that) {
    try {
      int v = Integer.compare(this.tag.op.priority, that.tag.op.priority);
      if (v != 0) return v;
      return this.isPerCollectiontag() && that.isPerCollectiontag() ?
          Integer.compare(this.replica.op.priority, that.replica.op.priority) :
          0;
    } catch (NullPointerException e) {
      System.out.println("this: " + Utils.toJSONString(this));
      System.out.println("thAt: " + Utils.toJSONString(that));
      throw e;
    }
  }

  static class Condition {
    final String name;
    final Object val;
    final Operand op;

    Condition(String name, Object val, Operand op) {
      this.name = name;
      this.val = val;
      this.op = op;
    }

    TestStatus match(Row row) {
      return op.match(val, row.getVal(name));
    }

    boolean isPass(Object inputVal) {
      return op.match(val, inputVal) == PASS;
    }

    boolean isPass(Row row) {
      return op.match(val, row.getVal(name)) == PASS;
    }

    @Override
    public boolean equals(Object that) {
      if (that instanceof Condition) {
        Condition c = (Condition) that;
        return Objects.equals(c.name, name) && Objects.equals(c.val, val) && c.op == op;
      }
      return false;
    }
  }

  static Condition parse(String s, Map m) {
    Object expectedVal = null;
    Object val = m.get(s);
    try {
      String conditionName = s.trim();
      String value = val == null ? null : String.valueOf(val).trim();
      Operand operand = null;
      if ((expectedVal = WILDCARD.parse(value)) != null) {
        operand = WILDCARD;
      } else if ((expectedVal = NOT_EQUAL.parse(value)) != null) {
        operand = NOT_EQUAL;
      } else if ((expectedVal = GREATER_THAN.parse(value)) != null) {
        operand = GREATER_THAN;
      } else if ((expectedVal = LESS_THAN.parse(value)) != null) {
        operand = LESS_THAN;
      } else {
        operand = EQUAL;
        expectedVal = EQUAL.parse(value);
      }

      return new Condition(conditionName, expectedVal, operand);

    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid tag : " + s + ":" + val, e);
    }
  }


  TestStatus test(Row row) {
    AtomicReference<TestStatus> result = new AtomicReference<>(NOT_APPLICABLE);
    if (isPerCollectiontag()) {

      for (Map.Entry<String, Map<String, List<ReplicaInfo>>> colls : row.replicaInfo.entrySet()) {
        if (result.get() == FAIL) break;
        if (!collection.isPass(colls.getKey())) continue;
        int count = 0;
        for (Map.Entry<String, List<ReplicaInfo>> shards : colls.getValue().entrySet()) {
          if (!shard.isPass(shards.getKey()) || result.get() == FAIL) break;
          count += shards.getValue().size();
          if (shard.val.equals(EACH)) testReplicaCount(row, result, count);
          if (EACH.equals(shard.val)) count = 0;
        }
        if (shard.val.equals(ANY)) testReplicaCount(row, result, count);
      }
    } else {
      if (!tag.isPass(row)) result.set(TestStatus.FAIL);
    }
    if (result.get() == FAIL)
      row.violations.add(this);
    return result.get();

  }

  private void testReplicaCount(Row row, AtomicReference<TestStatus> result, int count) {
    if ("node".equals(tag.name)) if (!tag.isPass(row.node)) return;
    boolean checkCount = replica.op.match(replica.val, 0) != PASS || count > 0;
    if (replica.op == WILDCARD && count > 0 && !tag.isPass(row)) {//nodeRole:'!overseer', strict:false
      result.set(FAIL);
    } else if (checkCount && !replica.isPass(count)) {
      if (tag.op != WILDCARD && tag.isPass(row)) {
        result.set(FAIL);
      } else {
        result.set(FAIL);
      }
    }
  }

  public boolean isStrict() {
    return strict;
  }

  @Override
  public String toString() {
    return Utils.toJSONString(original);
  }

  @Override
  public void writeMap(EntryWriter ew) throws IOException {
    for (Map.Entry<String, Object> e : original.entrySet()) ew.put(e.getKey(), e.getValue());
  }

  enum TestStatus {
    NOT_APPLICABLE, FAIL, PASS
  }

  private static final Set<String> IGNORE_TAGS = new HashSet<>(Arrays.asList(REPLICA, COLLECTION, SHARD, "strict"));
}
