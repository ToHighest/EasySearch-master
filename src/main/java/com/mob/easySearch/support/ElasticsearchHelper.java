/*
 * Copyright 2015-2020 msun.com All right reserved.
 */
package com.mob.easySearch.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeRequest;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.mapping.delete.DeleteMappingRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.hppc.ObjectLookupContainer;
import org.elasticsearch.common.hppc.cursors.ObjectCursor;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.ImmutableSettings.Builder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsBuilder;
import org.elasticsearch.search.aggregations.metrics.max.MaxBuilder;
import org.elasticsearch.search.aggregations.metrics.tophits.TopHits;
import org.elasticsearch.search.aggregations.metrics.tophits.TopHitsBuilder;
import org.elasticsearch.search.sort.SortOrder;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.lamfire.json.JSON;
import com.lamfire.logger.Logger;
import com.lamfire.logger.LoggerFactory;

/**
 * @author zxc Jun 13, 2016 4:20:25 PM
 */
public class ElasticsearchHelper {

    private static final Logger _ = LoggerFactory.getLogger(ElasticsearchHelper.class);

    private Client              client;
    private String              clusterName;

    public ElasticsearchHelper(String clusterName, String host, int port) {
        this.client = makeClient(clusterName, host, port);
        this.clusterName = clusterName;
    }

    public ElasticsearchHelper(String clusterName, String[] nodes) {
        List<Map<String, Object>> _nodes = Lists.newArrayList();
        for (String node : nodes) {
            Map<String, Object> map = Maps.newHashMap();
            map.put("host", StringUtils.split(node, ":")[0]);
            map.put("port", StringUtils.split(node, ":")[1]);
            _nodes.add(map);
        }
        this.client = makeClient(clusterName, _nodes);
        this.clusterName = clusterName;
    }

    public ElasticsearchHelper(String clusterName, List<Map<String, Object>> nodes) {
        this.client = makeClient(clusterName, nodes);
        this.clusterName = clusterName;
    }

    public String getClusterName() {
        return this.clusterName;
    }

    public Client getClient() {
        return this.client;
    }

    /**
     * 创建elasticsearch客户端
     * 
     * @param host
     * @param port
     * @return
     */
    @SuppressWarnings("resource")
    protected static Client makeClient(String clusterName, String host, int port) {
        Builder builder = ImmutableSettings.settingsBuilder();
        if (StringUtils.isNotBlank(clusterName)) builder.put("cluster.name", clusterName);
        Settings settings = builder.build();
        return new TransportClient(settings).addTransportAddress(new InetSocketTransportAddress(host, port));
    }

    /**
     * 创建elasticsearch客户端
     * 
     * @param addrMap
     * @return
     */
    protected static Client makeClient(String clusterName, List<Map<String, Object>> nodes) {
        Builder builder = ImmutableSettings.settingsBuilder();
        if (StringUtils.isNotBlank(clusterName)) builder.put("cluster.name", clusterName);
        Settings settings = builder.build();
        TransportClient client = new TransportClient(settings);
        for (Map<String, Object> addr : nodes) {
            client.addTransportAddress(new InetSocketTransportAddress((String) addr.get("host"),
                                                                      Integer.parseInt((String) addr.get("port"))));
        }
        return client;
    }

    /**
     * 全文检索,关键词查询
     * 
     * @param indexName
     * @param indexType
     * @param pageno
     * @param pagesize
     * @param q
     * @param filters
     * @param matchField
     * @return
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> query(String indexName, String indexType, int pageno, int pagesize, String q,
                                     Map<String, Object[]> filters, Set<String> matchField,
                                     Table<String, String, Object> ranges) {
        _.info("search query start");
        if (StringUtils.isEmpty(q)) q = "*";
        Set<String> fields = matchField;
        Set<String> allFields = Sets.newHashSet();
        GetMappingsResponse mappingsRes = getMapping(indexName, indexType);
        try {
            Map<String, Object> sourceMap = mappingsRes.mappings().get(indexName).get(indexType).getSourceAsMap();
            Map<String, Object> _sourceMap = (Map<String, Object>) sourceMap.get("properties");
            allFields.addAll(_sourceMap.keySet());
        } catch (Exception e) {
            _.error("queryString error!", e);
        }

        // 分词查询
        QueryStringQueryBuilder queryStringBuilder = new QueryStringQueryBuilder(q);
        queryStringBuilder.useDisMax(true);
        for (String field : fields)
            queryStringBuilder.field(field);
        // 过滤条件
        BoolFilterBuilder boolFilter = null;
        if (filters != null && filters.size() != 0) {
            boolFilter = FilterBuilders.boolFilter();
            for (Entry<String, Object[]> entry : filters.entrySet()) {
                if (allFields.contains(entry.getKey())) {
                    boolFilter.must(FilterBuilders.inFilter(entry.getKey(), entry.getValue()));
                }
            }
        }
        // 区间查询
        List<RangeFilterBuilder> rangeList = Lists.newArrayList();
        for (Entry<String, Map<String, Object>> range : ranges.rowMap().entrySet()) {
            RangeFilterBuilder rangeFilter = new RangeFilterBuilder(range.getKey());
            for (Entry<String, Object> row : range.getValue().entrySet()) {
                if (StringUtils.equals(row.getKey(), "gt")) rangeFilter.gt(row.getValue());
                if (StringUtils.equals(row.getKey(), "lt")) rangeFilter.lt(row.getValue());
                if (StringUtils.equals(row.getKey(), "gte")) rangeFilter.gte(row.getValue());
                if (StringUtils.equals(row.getKey(), "lte")) rangeFilter.lte(row.getValue());
            }
            rangeList.add(rangeFilter);
        }
        if (rangeList.size() > 0) {
            if (boolFilter == null) boolFilter = FilterBuilders.boolFilter();
            boolFilter.should(rangeList.toArray(new RangeFilterBuilder[] {}));
        }
        FilteredQueryBuilder query = QueryBuilders.filteredQuery(queryStringBuilder, boolFilter);

        SearchRequestBuilder search = makeSearchRequestBuilder(indexName, indexType).setQuery(query)//
        .setFrom((pageno - 1) * pagesize)//
        .setSize(pagesize)//
        .setSearchType(SearchType.DFS_QUERY_THEN_FETCH);

        SearchResponse response = search.execute().actionGet();
        long total = response.getHits().getTotalHits();
        List<Map<String, Object>> list = result(response);
        Map<String, Object> result = Maps.newHashMap();
        result.put("list", (list != null && list.size() > 0) ? list : Lists.newArrayList());
        result.put("pageno", pageno);
        result.put("pagesize", pagesize);
        result.put("total", total);
        return result;
    }

    /**
     * 聚合查询
     * 
     * <pre>
     * 多字段聚合
     * 通常情况，terms聚合都是仅针对于一个字段的聚合。因为该聚合是需要把词条放入一个哈希表中，如果多个字段就会造成n^2的内存消耗。
     * 不过，对于多字段，ES也提供了下面两种方式：
     * 1 使用脚本合并字段
     * 2 使用copy_to方法，合并两个字段，创建出一个新的字段，对新字段执行单个字段的聚合
     * </pre>
     * 
     * @param topOnly(true=仅显示一个,false=显示多个)
     * @param indexName
     * @param indexType
     * @param pageno
     * @param pagesize
     * @param q
     * @param filters
     * @param matchField
     * @param aggregation
     * @return
     */
    @SuppressWarnings({ "unchecked" })
    public Map<String, Object> aggr(String indexName, String indexType, String q, Map<String, Object[]> filters,
                                    Set<String> matchField, Set<String> aggregation,
                                    Table<String, String, Object> ranges, boolean topOnly) {
        _.info("search aggregation start");
        if (StringUtils.isEmpty(q)) q = "*";
        Set<String> fields = matchField;
        Set<String> allFields = Sets.newHashSet();
        GetMappingsResponse mappingsRes = getMapping(indexName, indexType);
        try {
            Map<String, Object> sourceMap = mappingsRes.mappings().get(indexName).get(indexType).getSourceAsMap();
            Map<String, Object> _sourceMap = (Map<String, Object>) sourceMap.get("properties");
            allFields.addAll(_sourceMap.keySet());
        } catch (Exception e) {
            _.error("queryString error!", e);
        }

        // 分词查询
        QueryStringQueryBuilder queryStringBuilder = new QueryStringQueryBuilder(q);
        queryStringBuilder.useDisMax(true);
        for (String field : fields)
            queryStringBuilder.field(field);
        // 过滤条件
        BoolFilterBuilder boolFilter = null;
        if (filters != null && filters.size() != 0) {
            boolFilter = FilterBuilders.boolFilter();
            for (Entry<String, Object[]> entry : filters.entrySet()) {
                if (allFields.contains(entry.getKey())) {
                    boolFilter.must(FilterBuilders.inFilter(entry.getKey(), entry.getValue()));
                }
            }
        }
        // 区间查询
        List<RangeFilterBuilder> rangeList = Lists.newArrayList();
        for (Entry<String, Map<String, Object>> range : ranges.rowMap().entrySet()) {
            RangeFilterBuilder rangeFilter = new RangeFilterBuilder(range.getKey());
            for (Entry<String, Object> row : range.getValue().entrySet()) {
                if (StringUtils.equals(row.getKey(), "gt")) rangeFilter.gt(row.getValue());
                if (StringUtils.equals(row.getKey(), "lt")) rangeFilter.lt(row.getValue());
                if (StringUtils.equals(row.getKey(), "gte")) rangeFilter.gte(row.getValue());
                if (StringUtils.equals(row.getKey(), "lte")) rangeFilter.lte(row.getValue());
            }
            rangeList.add(rangeFilter);
        }
        if (rangeList.size() > 0) {
            if (boolFilter == null) boolFilter = FilterBuilders.boolFilter();
            boolFilter.should(rangeList.toArray(new RangeFilterBuilder[] {}));
        }
        FilteredQueryBuilder query = QueryBuilders.filteredQuery(queryStringBuilder, boolFilter);

        SearchRequestBuilder search = makeSearchRequestBuilder(indexName, indexType).setQuery(query)//
        .setSize(0)// size为0,结果返回全部聚合查询数据,也就是Global
        .setSearchType(SearchType.DFS_QUERY_THEN_FETCH);

        // 聚合的key
        String key = "";
        // 按字段去重
        List<String> aggList = Lists.newArrayList();
        for (String agg : aggregation) {
            if (allFields.contains(agg)) aggList.add(agg);
        }
        TermsBuilder termsBuilder = AggregationBuilders.terms("top-tags").size(0);
        // 使用term field聚合
        if (aggList.size() == 1) {
            key = aggList.get(0);
            termsBuilder.field(key);
        }
        // 使用term script聚合
        if (aggList.size() > 1) {
            List<String> _aggList = Lists.newArrayList();
            for (String aggStr : aggList) {
                _aggList.add("doc." + aggStr + ".value");
            }
            key = StringUtils.join(aggList, "-");
            termsBuilder.script("[" + StringUtils.join(_aggList, ",") + "].join(\"-\")");
        }
        // terms sort by max_score
        termsBuilder.order(Terms.Order.aggregation("max_score", false));

        TopHitsBuilder topHitsBuilder = AggregationBuilders.topHits("top-tags-record")//
        .setFetchSource(allFields.toArray(new String[] {}), null);
        if (topOnly) topHitsBuilder.setSize(1);
        if (!topOnly) topHitsBuilder.setSize(320);
        MaxBuilder maxBuilder = AggregationBuilders.max("max_score").script("_score");
        termsBuilder.subAggregation(topHitsBuilder);
        termsBuilder.subAggregation(maxBuilder);
        search.addAggregation(termsBuilder);

        SearchResponse response = search.execute().actionGet();
        long total = 0l;

        Aggregations agg = response.getAggregations();
        Terms types = agg.get("top-tags");
        Collection<Terms.Bucket> collection = types.getBuckets();
        total += collection.size();
        _.debug("aggregation total=" + total);

        List<Map<String, Object>> list = Lists.newLinkedList();
        for (Terms.Bucket bucket : collection) {
            TopHits topHits = bucket.getAggregations().get("top-tags-record");
            if (topOnly) {
                list.addAll(result(topHits));
            } else {
                Map<String, Object> _data = Maps.newHashMap();
                _data.put(key, bucket.getKey());
                _data.put("hits", result(topHits));
                list.add(_data);
            }
        }

        // 集合去重
        Set<String> hashcode = Sets.newHashSet();
        Set<Map<String, Object>> sets = Sets.newLinkedHashSet();
        for (Map<String, Object> _ : list) {
            boolean contains = _ != null && hashcode.contains(_.toString());
            if (_ != null && !contains) {
                hashcode.add(_.toString());
                sets.add(_);
            }
        }
        Map<String, Object> result = Maps.newHashMap();
        result.put("total", (sets != null && sets.size() > 0) ? sets.size() : 0);
        result.put("list", (sets != null && sets.size() > 0) ? sets : Sets.newHashSet());
        return result;
    }

    /**
     * match查询
     * 
     * @param indexName
     * @param indexType
     * @param pageno
     * @param pagesize
     * @param params
     * @return
     */
    public Map<String, Object> match(String indexName, String indexType, int pageno, int pagesize,
                                     Map<String, Object> params) {
        BoolQueryBuilder query = QueryBuilders.boolQuery();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null || StringUtils.isEmpty(entry.getKey())) continue;
            query.must(QueryBuilders.matchQuery(entry.getKey(), entry.getValue()));
        }

        SearchRequestBuilder search = makeSearchRequestBuilder(indexName, indexType);
        SearchResponse response = search.setQuery(query)//
        .setFrom((pageno - 1) * pagesize)//
        .setSize(pagesize)//
        .addSort("createat", SortOrder.DESC)//
        .execute().actionGet();

        long total = response.getHits().getTotalHits();
        List<Map<String, Object>> list = result(response);
        Map<String, Object> result = Maps.newHashMap();
        if (total > 0) result.put("total", total);
        if (list != null && list.size() > 0) result.put("list", list);
        return result;
    }

    /**
     * 新建MAPPING
     * 
     * @param mapper
     */
    public void createMapping(String indexName, String indexType, Map<String, Map<String, Object>> fields) {
        XContentBuilder mapping = getMapping(indexName, indexType, fields);
        createMapping(indexName, indexType, mapping);
    }

    /**
     * 查询MAPPING
     * 
     * @param mapper
     */
    public GetMappingsResponse getMapping(String indexName, String indexType) {
        GetMappingsRequest mappingsRequest = new GetMappingsRequest().indices(indexName).types(indexType);
        return getClient().admin().indices().getMappings(mappingsRequest).actionGet();
    }

    public GetMappingsResponse getMapping() {
        GetMappingsRequest mappingsRequest = new GetMappingsRequest();
        return getClient().admin().indices().getMappings(mappingsRequest).actionGet();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> allMapping() {
        Map<String, Object> map = Maps.newLinkedHashMap();
        try {
            GetMappingsResponse mappingsRes = getMapping();
            ObjectLookupContainer<String> indexNames = mappingsRes.mappings().keys();
            for (ObjectCursor<String> indexName : indexNames) {
                if (com.lamfire.utils.StringUtils.contains(indexName.value, "marvel")) continue;
                map.put(indexName.value, Maps.newLinkedHashMap());
                ObjectLookupContainer<String> indexTypes = mappingsRes.mappings().get(indexName.value).keys();
                for (ObjectCursor<String> indexType : indexTypes) {
                    if (com.lamfire.utils.StringUtils.contains(indexType.value, "marvel")) continue;
                    Map<String, Object> sourceMap = mappingsRes.mappings().get(indexName.value).get(indexType.value).getSourceAsMap();
                    ((Map<String, Object>) map.get(indexName.value)).put(indexType.value, sourceMap.get("properties"));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return map;
    }

    /**
     * 删除MAPPING
     * 
     * @param indexName
     * @param indexType
     */
    public void deleteMapping(String indexName, String indexType) {
        DeleteMappingRequest mappingRequest = Requests.deleteMappingRequest(indexName).types(indexType);
        getClient().admin().indices().deleteMapping(mappingRequest);
    }

    /**
     * 新建空索引库
     * 
     * @param indexName
     */
    public void createIndex(String indexName) {
        getClient().admin().indices().prepareCreate(indexName).execute().actionGet();
    }

    /**
     * 是否存在索引库
     * 
     * @param indexName
     * @return
     */
    public boolean existsIndex(String indexName) {
        IndicesExistsRequest request = new IndicesExistsRequest(indexName);
        return getClient().admin().indices().exists(request).actionGet().isExists();
    }

    /**
     * 通过索引ID获得
     * 
     * @param indexName
     * @param indexType
     * @param id
     * @return
     */
    public GetResponse get(String indexName, String indexType, String id) {
        GetRequest request = new GetRequest(indexName, indexType, id);
        return getClient().get(request).actionGet();
    }

    /**
     * 创建查询构建器
     * 
     * @param indexName
     * @return
     */
    public SearchRequestBuilder makeSearchRequestBuilder(String indexName, String indexType) {
        return getClient().prepareSearch(indexName).setTypes(indexType);
    }

    /**
     * 索引数据
     * 
     * @param indexName
     * @param indexType
     * @param id
     * @param source
     */
    public void index(String indexName, String indexType, String id, Map<String, Object> source) {
        getIndexRequestBuilder(indexName, indexType, id, source).execute().actionGet();
    }

    /**
     * 批量索引数据
     * 
     * @param indexName
     * @param indexType
     * @param id
     * @param entitys
     */
    public void indexes(String indexName, String indexType, String id, List<Map<String, Object>> entitys) {
        BulkRequest bulk = Requests.bulkRequest();
        for (Object entity : entitys) {
            IndexRequestBuilder indexReq = getIndexRequestBuilder(indexName, indexType, id, JSON.fromJavaObject(entity));
            bulk.add(indexReq.request());
        }
        bulk(bulk);
    }

    public int bulk(String indexName, String indexType, File file) throws Exception {
        FileReader fr = new FileReader(file);
        BufferedReader br = new BufferedReader(fr);
        String line = null;
        BulkRequestBuilder bulkRequest = client.prepareBulk();
        int count = 0;
        while ((line = br.readLine()) != null) {
            try {
                bulkRequest.add(client.prepareIndex("test", "article").setSource(line));
                if (count % 10 == 0) bulkRequest.execute().actionGet();
                count++;
            } catch (Exception e) {
                _.error("bulk error!", e);
            }
        }
        bulkRequest.execute().actionGet();
        br.close();
        return count;
    }

    /**
     * 清空索引数据
     * 
     * @param indexName
     */
    public void clearIndex(String indexName) {
        getClient().prepareDelete().setIndex(indexName).execute().actionGet();
    }

    /**
     * 删除索引库
     * 
     * @param indexName
     */
    public void dropIndex(String indexName) {
        DeleteIndexRequest request = new DeleteIndexRequest(indexName);
        getClient().admin().indices().delete(request);
    }

    public void bulk(BulkRequest req) {
        getClient().bulk(req).actionGet();
    }

    public void bulk(List<IndexRequestBuilder> requests) {
        BulkRequest bulk = Requests.bulkRequest();
        for (IndexRequestBuilder req : requests)
            bulk.add(req.request());
        bulk(bulk);
    }

    public AnalyzeResponse analyzer(String indexName, String text, String analyzer) {
        AnalyzeRequest request = new AnalyzeRequest(indexName, text).analyzer("ik");
        if (StringUtils.isNotBlank(analyzer)) {
            request.analyzer(analyzer);
        }
        return getClient().admin().indices().analyze(request).actionGet();
    }

    // *********************************************** private method *******************************************//

    private synchronized XContentBuilder getMapping(String indexName, String indexType,
                                                    Map<String, Map<String, Object>> fields) {
        try {
            XContentBuilder mapping = XContentFactory.jsonBuilder();
            mapping.startObject().startObject(indexType).startObject("properties");
            for (Entry<String, Map<String, Object>> field : fields.entrySet()) {
                String name = field.getKey();
                Map<String, Object> indexed = field.getValue();
                String type = (String) indexed.get("type");
                boolean store = (boolean) (indexed.get("store") == null ? true : indexed.get("store"));
                boolean analyzed = (boolean) (indexed.get("analyzed") == null ? true : indexed.get("analyzed"));
                boolean searched = (boolean) (indexed.get("searched") == null ? true : indexed.get("searched"));
                String analyzer = (String) indexed.get("analyzer");

                if (StringUtils.isEmpty(type)) type = "string";
                mapping.startObject(name).field("type", type).field("store", store);
                if (!analyzed) mapping.field("index", "not_analyzed");
                if (searched) mapping.field("searched", searched);
                if (StringUtils.isNotEmpty(analyzer)) mapping.field("analyzer", analyzer);
                if (indexed.get("copy_to") != null) mapping.field("copy_to", indexed.get("copy_to"));
                mapping.endObject();
            }
            mapping.endObject().endObject().endObject();
            return mapping;
        } catch (Exception e) {
            _.error("can not create mapping for '" + indexName + "'", e);
        }
        return null;
    }

    private void createMapping(String indexName, String indexType, XContentBuilder mapping) {
        if (mapping == null) return;
        PutMappingRequest mappingRequest = new PutMappingRequest(indexName);
        mappingRequest.type(indexType);
        mappingRequest.source(mapping);
        getClient().admin().indices().putMapping(mappingRequest).actionGet();
    }

    private List<Map<String, Object>> result(SearchResponse res) {
        List<Map<String, Object>> result = Lists.newArrayList();
        SearchHits totalHits = res.getHits();
        SearchHit[] hits = totalHits.getHits();
        for (SearchHit hit : hits)
            result.add(hit.getSource());
        return result;
    }

    private List<Map<String, Object>> result(TopHits topHits) {
        List<Map<String, Object>> result = Lists.newArrayList();
        SearchHits totalHits = topHits.getHits();
        SearchHit[] hits = totalHits.getHits();
        for (SearchHit hit : hits)
            result.add(hit.getSource());
        return result;
    }

    private IndexRequestBuilder getIndexRequestBuilder(String indexName, String indexType, Object id,
                                                       Map<String, Object> source) {
        if (id == null || StringUtils.isBlank(id.toString())) return null;
        return getClient()//
        .prepareIndex(indexName, indexType, id.toString())//
        .setConsistencyLevel(WriteConsistencyLevel.ONE)//
        .setSource(source);
    }

    // 自定义正则表达式匹配
    public static boolean customMatches(String pattern, String value) {
        if (StringUtils.isBlank(pattern) || StringUtils.isBlank(value)) return false;
        Pattern p = Pattern.compile(pattern);
        return p.matcher(value).matches();
    }
}
