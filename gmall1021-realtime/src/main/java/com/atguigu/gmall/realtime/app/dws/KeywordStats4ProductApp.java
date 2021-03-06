package com.atguigu.gmall.realtime.app.dws;

import com.atguigu.gmall.realtime.app.func.KeywordProductC2RUDTF;
import com.atguigu.gmall.realtime.app.func.KeywordUDTF;
import com.atguigu.gmall.realtime.bean.KeywordStats;
import com.atguigu.gmall.realtime.utils.ClickHouseUtil;
import com.atguigu.gmall.realtime.utils.MyKafkaUtil;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

/**
 * 商品行为关键字主题宽表计算
 */
public class KeywordStats4ProductApp {
    public static void main(String[] args) throws Exception {
        // TODO: 2021/5/5 基本环境准备
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // 设置并行度
        env.setParallelism(4);
        // 检查点相关设置
//        env.enableCheckpointing(5000, CheckpointingMode.EXACTLY_ONCE);
//        env.getCheckpointConfig().setCheckpointTimeout(60000);
//        env.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
//        env.setStateBackend(new FsStateBackend("hdfs://hadoop102:9820/gmall/flink/checkpoint/"));
//        System.setProperty("HADOOP_USER_NAME", "atguigu");
        // TODO: 2021/5/5 定义流环境
        EnvironmentSettings settings = EnvironmentSettings.newInstance().inStreamingMode().build();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env, settings);

        // TODO: 2021/5/5 注册自定义函数
        tableEnv.createTemporarySystemFunction("keywordProductC2R", KeywordProductC2RUDTF.class);
        tableEnv.createTemporarySystemFunction("ik_analyze", KeywordUDTF.class);


        // TODO: 2021/5/5 将数据源定义为动态表
        String groupId = "keyword_stats_app";
        String productStatsSourceTopic = "dws_product_stats";

        tableEnv.executeSql("CREATE TABLE product_stats (spu_name STRING, " +
                "click_ct BIGINT," +
                "cart_ct BIGINT," +
                "order_ct BIGINT ," +
                "stt STRING,edt STRING ) " +
                "  WITH (" + MyKafkaUtil.getKafkaDDL(productStatsSourceTopic, groupId) + ")");

        // TODO: 2021/5/5 聚合计算
        Table keywordStatsProduct = tableEnv.sqlQuery("select keyword,ct,source, " +
                "DATE_FORMAT(stt,'yyyy-MM-dd HH:mm:ss')  stt," +
                "DATE_FORMAT(edt,'yyyy-MM-dd HH:mm:ss') as edt, " +
                "UNIX_TIMESTAMP()*1000 ts from product_stats  , " +
                "LATERAL TABLE(ik_analyze(spu_name)) as T(keyword) ," +
                "LATERAL TABLE(keywordProductC2R( click_ct ,cart_ct,order_ct)) as T2(ct,source)");


        // TODO: 2021/5/5 转换为数据流
        DataStream<KeywordStats> keywordStatsProductStream = tableEnv.<KeywordStats>toAppendStream(keywordStatsProduct, KeywordStats.class);
        keywordStatsProductStream.print("###");

        // TODO: 2021/5/5 写入到ClickHouse
        keywordStatsProductStream.addSink(
                ClickHouseUtil.<KeywordStats>getJdbcSink(
                        "insert into keyword_stats_2021(keyword,ct,source,stt,edt,ts)  " +
                                "values(?,?,?,?,?,?)"));

        env.execute();

    }
}
