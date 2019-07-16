/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.bigtable

import java.nio.charset.Charset

import com.google.bigtable.admin.v2._
import com.google.bigtable.admin.v2.ModifyColumnFamiliesRequest.Modification
import com.google.cloud.bigtable.config.BigtableOptions
import com.google.cloud.bigtable.grpc._
import com.google.protobuf.{ByteString, Duration => ProtoDuration}
import org.joda.time.Duration
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.util.Try

/**
 * Bigtable Table Admin API helper commands.
 */
object TableAdmin {
  private val log: Logger = LoggerFactory.getLogger(TableAdmin.getClass)

  private def adminClient[A](
    bigtableOptions: BigtableOptions
  )(f: BigtableTableAdminClient => A): Try[A] = {
    val channel =
      ChannelPoolCreator.createPool(bigtableOptions)
    val executorService =
      BigtableSessionSharedThreadPools.getInstance().getRetryExecutor
    val client = new BigtableTableAdminGrpcClient(channel, executorService, bigtableOptions)

    val result = Try(f(client))
    channel.shutdownNow()
    result
  }

  /**
   * Retrieves a set of tables from the given instancePath.
   *
   * @param client Client for calling Bigtable.
   * @param instancePath String of the form "projects/$project/instances/$instance".
   * @return
   */
  private def fetchTables(client: BigtableTableAdminClient, instancePath: String): Set[String] = {
    client
      .listTables(
        ListTablesRequest
          .newBuilder()
          .setParent(instancePath)
          .build()
      )
      .getTablesList
      .asScala
      .map(t => t.getName)
      .toSet
  }

  /**
   * Ensure that tables and column families exist.
   * Checks for existence of tables or creates them if they do not exist.  Also checks for
   * existence of column families within each table and creates them if they do not exist.
   *
   * @param tablesAndColumnFamilies A map of tables and column families.  Keys are table names.
   *                                Values are a list of column family names.
   */
  def ensureTables(
    bigtableOptions: BigtableOptions,
    tablesAndColumnFamilies: Map[String, List[String]]
  ): Unit = {
    val project = bigtableOptions.getProjectId
    val instance = bigtableOptions.getInstanceId
    val instancePath = s"projects/$project/instances/$instance"

    log.info("Ensuring tables and column families exist in instance {}", instance)

    adminClient(bigtableOptions) { client =>
      val existingTables = fetchTables(client, instancePath)

      for ((table, columnFamilies) <- tablesAndColumnFamilies) {
        val tablePath = s"$instancePath/tables/$table"

        if (!existingTables.contains(tablePath)) {
          log.info("Creating table {}", table)
          client.createTable(
            CreateTableRequest
              .newBuilder()
              .setParent(instancePath)
              .setTableId(table)
              .build()
          )
        } else {
          log.info("Table {} exists", table)
        }

        ensureColumnFamilies(client, tablePath, columnFamilies)
      }
    }.get
  }

  /**
   * Ensure that column families exist.
   * Checks for existence of column families and creates them if they don't exist.
   *
   * @param tablePath A full table path that the bigtable API expects, in the form of
   *                  `projects/projectId/instances/instanceId/tables/tableId`
   * @param columnFamilies A list of column family names.
   */
  private def ensureColumnFamilies(
    client: BigtableTableAdminClient,
    tablePath: String,
    columnFamilies: List[String]
  ): Unit = {

    val tableInfo =
      client.getTable(GetTableRequest.newBuilder().setName(tablePath).build)

    val modifications: List[Modification] = columnFamilies.collect {
      case cf if !tableInfo.containsColumnFamilies(cf) =>
        Modification
          .newBuilder()
          .setId(cf)
          .setCreate(ColumnFamily.newBuilder())
          .build()
    }

    if (modifications.isEmpty) {
      log.info(s"Column families $columnFamilies exist in $tablePath")
    } else {
      log.info(s"Creating column families $columnFamilies in $tablePath")
      client.modifyColumnFamily(
        ModifyColumnFamiliesRequest
          .newBuilder()
          .setName(tablePath)
          .addAllModifications(modifications.asJava)
          .build
      )
      ()
    }
  }

  /**
   * Set cell expiration.
   * Adds or modifies a cell expiration rule for the provided tables and column families.
   *
   * @param tablesAndColumnFamilies A map of tables and column families.  Keys are table names.
   *                                Values are a list of column family names.
   * @param cellExpiration The duration before which garbage collection of a cell may occur.
   *                       Note: minimum granularity is second.
   */
  def setCellExpiration(bigtableOptions: BigtableOptions,
                        tablesAndColumnFamilies: Map[String, List[String]],
                        cellExpiration: Duration): Unit = {
    val protoDuration = ProtoDuration.newBuilder.setSeconds(cellExpiration.getStandardSeconds)
    val gcRule = GcRule.newBuilder.setMaxAge(protoDuration).build
    setGcRule(bigtableOptions, tablesAndColumnFamilies, gcRule)
  }

  /**
   * Set GcRule.
   * Adds or modifies a GcRule for the provided tables and column families.
   *
   * @param tablesAndColumnFamilies A map of tables and column families.  Keys are table names.
   *                                Values are a list of column family names.
   * @param gcRule The gcRule to set on the provided tables and families.
   */
  private def setGcRule(bigtableOptions: BigtableOptions,
                        tablesAndColumnFamilies: Map[String, List[String]],
                        gcRule: GcRule): Unit = {
    val project = bigtableOptions.getProjectId
    val instance = bigtableOptions.getInstanceId
    val instancePath = s"projects/$project/instances/$instance"

    val tablePathsAndColumnFamilies = tablesAndColumnFamilies.map { case (table, cfs) =>
      s"$instancePath/tables/$table" -> cfs
    }

    adminClient(bigtableOptions) { client =>

      val existingTables = fetchTables(client, instancePath)
      val nonExistent = tablePathsAndColumnFamilies.keySet.diff(existingTables)

      nonExistent.foreach { table =>
        log.info(s"Skipping modification for non-existent table $table")
      }

      (tablePathsAndColumnFamilies -- nonExistent).foreach { case (tablePath, columnFamilies) =>

        val tableInfo = client.getTable(GetTableRequest.newBuilder.setName(tablePath).build)

        val modifications: List[Modification] =
          columnFamilies
            .filter { cf =>
              val exists = tableInfo.containsColumnFamilies(cf)
              if (!exists)
                log.info(
                  s"Skipping modification for non-existent column family $cf in table $tablePath")
              exists
            }
            .map { cf =>
              Modification
                .newBuilder()
                .setId(cf)
                .setUpdate(ColumnFamily
                  .newBuilder()
                  .setGcRule(gcRule))
                .build()
            }

        if (modifications.nonEmpty) {
          log.info(s"Updating gcRule for column families $columnFamilies in $tablePath")
          client.modifyColumnFamily(
            ModifyColumnFamiliesRequest
              .newBuilder()
              .setName(tablePath)
              .addAllModifications(modifications.asJava)
              .build)
        }
      }
    }.get
  }

  /**
   * Permanently deletes a row range from the specified table that match a particular prefix.
   *
   * @param table table name
   * @param rowPrefix row key prefix
   */
  def dropRowRange(bigtableOptions: BigtableOptions, table: String, rowPrefix: String): Try[Unit] =
    adminClient(bigtableOptions) { client =>
      val project = bigtableOptions.getProjectId
      val instance = bigtableOptions.getInstanceId
      val instancePath = s"projects/$project/instances/$instance"
      val tablePath = s"$instancePath/tables/$table"

      dropRowRange(tablePath, rowPrefix, client)
    }

  /**
   * Permanently deletes a row range from the specified table that match a particular prefix.
   *
   * @param tablePath A full table path that the bigtable API expects, in the form of
   *                  `projects/projectId/instances/instanceId/tables/tableId`
   * @param rowPrefix row key prefix
   */
  private def dropRowRange(
    tablePath: String,
    rowPrefix: String,
    client: BigtableTableAdminClient
  ): Unit = {
    val request = DropRowRangeRequest
      .newBuilder()
      .setName(tablePath)
      .setRowKeyPrefix(ByteString.copyFrom(rowPrefix, Charset.forName("UTF-8")))
      .build()

    client.dropRowRange(request)
  }

}
