// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.
package com.starrocks.statistic;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.common.Config;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.common.ErrorType;
import com.starrocks.sql.common.StarRocksPlannerException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StatisticsCollectJobFactory {
    private StatisticsCollectJobFactory() {
    }

    public static List<StatisticsCollectJob> buildStatisticsCollectJob(AnalyzeJob analyzeJob) {
        // The jobs need to be sorted in order of execution to avoid duplicate collections

        List<StatisticsCollectJob> statsJobs = Lists.newArrayList();
        if (StatsConstants.DEFAULT_ALL_ID == analyzeJob.getDbId()) {
            // all database
            List<Long> dbIds = GlobalStateMgr.getCurrentState().getDbIds();

            for (Long dbId : dbIds) {
                Database db = GlobalStateMgr.getCurrentState().getDb(dbId);
                if (null == db || StatisticUtils.statisticDatabaseBlackListCheck(db.getFullName())) {
                    continue;
                }

                for (Table table : db.getTables()) {
                    createJob(statsJobs, analyzeJob, db, table, null);
                }
            }
        } else if (StatsConstants.DEFAULT_ALL_ID == analyzeJob.getTableId()
                && StatsConstants.DEFAULT_ALL_ID != analyzeJob.getDbId()) {
            // all table
            Database db = GlobalStateMgr.getCurrentState().getDb(analyzeJob.getDbId());
            if (null == db) {
                return Collections.emptyList();
            }

            for (Table table : db.getTables()) {
                createJob(statsJobs, analyzeJob, db, table, null);
            }
        } else {
            //database or table is null mean database/table has been dropped
            Database db = GlobalStateMgr.getCurrentState().getDb(analyzeJob.getDbId());
            if (db == null) {
                return Collections.emptyList();
            }
            createJob(statsJobs, analyzeJob, db, db.getTable(analyzeJob.getTableId()), analyzeJob.getColumns());
        }

        return statsJobs;
    }

    public static StatisticsCollectJob buildStatisticsCollectJob(Database db, OlapTable table,
                                                                 List<Long> partitionIdList,
                                                                 List<String> columns,
                                                                 StatsConstants.AnalyzeType analyzeType,
                                                                 StatsConstants.ScheduleType scheduleType,
                                                                 Map<String, String> properties) {
        if (columns == null) {
            columns = table.getBaseSchema().stream().filter(d -> !d.isAggregated()).map(Column::getName)
                    .collect(Collectors.toList());
        }

        if (analyzeType.equals(StatsConstants.AnalyzeType.SAMPLE)) {
            return new SampleStatisticsCollectJob(db, table, columns,
                    StatsConstants.AnalyzeType.SAMPLE, scheduleType, properties);
        } else {
            if (partitionIdList == null) {
                partitionIdList = table.getPartitions().stream().map(Partition::getId).collect(Collectors.toList());
            }
            return new FullStatisticsCollectJob(db, table, partitionIdList, columns,
                    StatsConstants.AnalyzeType.FULL, scheduleType, properties);
        }
    }

    private static void createJob(List<StatisticsCollectJob> allTableJobMap, AnalyzeJob job,
                                  Database db, Table table, List<String> columns) {
        if (table == null || !table.isOlapOrLakeTable()) {
            return;
        }

        LocalDateTime updateTime = StatisticUtils.getTableLastUpdateTime(table);
        if (job.getWorkTime().isAfter(updateTime)) {
            return;
        }

        BasicStatsMeta basicStatsMeta = GlobalStateMgr.getCurrentAnalyzeMgr().getBasicStatsMetaMap().get(table.getId());
        if (basicStatsMeta != null && basicStatsMeta.getHealthy() > Config.statistic_auto_collect_ratio) {
            return;
        }

        if (job.getAnalyzeType().equals(StatsConstants.AnalyzeType.SAMPLE)) {
            allTableJobMap.add(buildStatisticsCollectJob(db, (OlapTable) table, null, columns,
                    job.getAnalyzeType(), job.getScheduleType(), job.getProperties()));
        } else if (job.getAnalyzeType().equals(StatsConstants.AnalyzeType.FULL)) {
            createFullStatsJob(allTableJobMap, job, basicStatsMeta, db, table, columns);
        } else {
            throw new StarRocksPlannerException("Unknown analyze type " + job.getAnalyzeType(), ErrorType.INTERNAL_ERROR);
        }
    }

    private static void createFullStatsJob(List<StatisticsCollectJob> allTableJobMap,
                                           AnalyzeJob job, BasicStatsMeta basicStatsMeta,
                                           Database db, Table table, List<String> columns) {
        StatsConstants.AnalyzeType analyzeType;
        if (((OlapTable) table).getPartitions().stream().anyMatch(
                p -> p.getDataSize() > Config.statistic_max_full_collect_data_size)) {
            analyzeType = StatsConstants.AnalyzeType.SAMPLE;
        } else {
            analyzeType = StatsConstants.AnalyzeType.FULL;
        }

        List<Partition> partitions = Lists.newArrayList(((OlapTable) table).getPartitions());
        List<Long> partitionIdList = new ArrayList<>();
        if (basicStatsMeta == null) {
            partitions.stream().map(Partition::getId).forEach(partitionIdList::add);
        } else {
            LocalDateTime statsLastUpdateTime = basicStatsMeta.getUpdateTime();
            for (Partition partition : partitions) {
                LocalDateTime partitionUpdateTime = StatisticUtils.getPartitionLastUpdateTime(partition);
                if (statsLastUpdateTime.isBefore(partitionUpdateTime)) {
                    partitionIdList.add(partition.getId());
                }
            }
        }
        if (!partitionIdList.isEmpty()) {
            allTableJobMap.add(buildStatisticsCollectJob(db, (OlapTable) table, partitionIdList, columns,
                    analyzeType, job.getScheduleType(), Maps.newHashMap()));
        }
    }
}
