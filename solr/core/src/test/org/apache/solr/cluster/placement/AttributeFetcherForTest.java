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

package org.apache.solr.cluster.placement;

import org.apache.solr.cluster.Node;
import org.apache.solr.cluster.SolrCollection;

import java.util.Set;

public class AttributeFetcherForTest implements AttributeFetcher {

  private final AttributeValues attributeValues;

  AttributeFetcherForTest(AttributeValues attributeValues) {
    this.attributeValues = attributeValues;
  }

  @Override
  public AttributeFetcher requestNodeSystemProperty(String name) {
    return this;
  }

  @Override
  public AttributeFetcher requestNodeMetric(NodeMetric<?> metric) {
    return this;
  }

  @Override
  public AttributeFetcher requestCollectionMetrics(SolrCollection solrCollection, Set<ReplicaMetric<?>> metricNames) {
    return this;
  }

  @Override
  public AttributeFetcher fetchFrom(Set<Node> nodes) {
    return this;
  }

  @Override
  public AttributeValues fetchAttributes() {
    return attributeValues;
  }
}
