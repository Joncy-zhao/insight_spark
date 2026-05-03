package com.insightspark.core.excel;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 核心：动态读取任意未知结构的 Excel 文件的监听器
 */
public class DynamicDataListener extends AnalysisEventListener<Map<Integer, String>> {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataListener.class);
    private Map<Integer, String> headMap;
    private final List<Map<Integer, String>> dataList = new ArrayList<>();

    // 1. 读取表头：每次解析新文件时都会触发
    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        this.headMap = headMap;
        log.info("成功抓取到表头信息: {}", headMap);
    }

    // 2. 逐行读取数据：一行一行地解析，避免内存撑爆
    @Override
    public void invoke(Map<Integer, String> data, AnalysisContext context) {
        dataList.add(data);
    }

    // 3. 收尾工作：所有数据读取完毕后触发
    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        log.info("Excel 所有的行已经解析完毕！共读取了 {} 条数据。", dataList.size());
    }

    public Map<Integer, String> getHeadMap() { return headMap; }
    public List<Map<Integer, String>> getDataList() { return dataList; }
}