/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.memory;

import com.facebook.presto.spi.ConnectorOutputTableHandle;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.testing.TestingNodeManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.spi.StandardErrorCode.ALREADY_EXISTS;
import static com.facebook.presto.testing.TestingConnectorSession.SESSION;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestMemoryMetadata
{
    private MemoryMetadata metadata;

    @BeforeMethod
    public void setUp()
    {
        metadata = new MemoryMetadata(new TestingNodeManager(), new MemoryConnectorId("test"));
    }

    @Test
    public void tableIsCreatedAfterCommits()
    {
        assertNoTables();

        SchemaTableName schemaTableName = new SchemaTableName("default", "temp_table");

        ConnectorOutputTableHandle table = metadata.beginCreateTable(
                SESSION,
                new ConnectorTableMetadata(schemaTableName, ImmutableList.of(), ImmutableMap.of()),
                Optional.empty());

        metadata.finishCreateTable(SESSION, table, ImmutableList.of());

        List<SchemaTableName> tables = metadata.listTables(SESSION, null);
        assertTrue(tables.size() == 1, "Expected only one table");
        assertTrue(tables.get(0).getTableName().equals("temp_table"), "Expected table with name 'temp_table'");
    }

    @Test
    public void tableAlreadyExists()
    {
        assertNoTables();

        SchemaTableName test1Table = new SchemaTableName("default", "test1");
        SchemaTableName test2Table = new SchemaTableName("default", "test2");
        metadata.createTable(SESSION, new ConnectorTableMetadata(test1Table, ImmutableList.of()));

        try {
            metadata.createTable(SESSION, new ConnectorTableMetadata(test1Table, ImmutableList.of()));
            fail("Should fail because table already exists");
        }
        catch (PrestoException ex) {
            assertEquals(ex.getErrorCode(), ALREADY_EXISTS.toErrorCode());
            assertEquals(ex.getMessage(), "Table [default.test1] already exists");
        }

        ConnectorTableHandle test1TableHandle = metadata.getTableHandle(SESSION, test1Table);
        metadata.createTable(SESSION, new ConnectorTableMetadata(test2Table, ImmutableList.of()));

        try {
            metadata.renameTable(SESSION, test1TableHandle, test2Table);
            fail("Should fail because table already exists");
        }
        catch (PrestoException ex) {
            assertEquals(ex.getErrorCode(), ALREADY_EXISTS.toErrorCode());
            assertEquals(ex.getMessage(), "Table [default.test2] already exists");
        }
    }

    @Test
    public void testActiveTableIds()
    {
        assertNoTables();

        SchemaTableName firstTableName = new SchemaTableName("default", "first_table");
        metadata.createTable(SESSION, new ConnectorTableMetadata(firstTableName, ImmutableList.of(), ImmutableMap.of()));

        MemoryTableHandle firstTableHandle = (MemoryTableHandle) metadata.getTableHandle(SESSION, firstTableName);
        Long firstTableId = firstTableHandle.getTableId();

        assertTrue(metadata.beginInsert(SESSION, firstTableHandle).getActiveTableIds().contains(firstTableId));

        SchemaTableName secondTableName = new SchemaTableName("default", "second_table");
        metadata.createTable(SESSION, new ConnectorTableMetadata(secondTableName, ImmutableList.of(), ImmutableMap.of()));

        MemoryTableHandle secondTableHandle = (MemoryTableHandle) metadata.getTableHandle(SESSION, secondTableName);
        Long secondTableId = secondTableHandle.getTableId();

        assertNotEquals(firstTableId, secondTableId);
        assertTrue(metadata.beginInsert(SESSION, secondTableHandle).getActiveTableIds().contains(firstTableId));
        assertTrue(metadata.beginInsert(SESSION, secondTableHandle).getActiveTableIds().contains(secondTableId));
    }

    @Test
    public void testReadTableBeforeCreationCompleted()
    {
        assertNoTables();

        SchemaTableName tableName = new SchemaTableName("default", "temp_table");

        ConnectorOutputTableHandle table = metadata.beginCreateTable(
                SESSION,
                new ConnectorTableMetadata(tableName, ImmutableList.of(), ImmutableMap.of()),
                Optional.empty());

        List<SchemaTableName> tableNames = metadata.listTables(SESSION, null);
        assertTrue(tableNames.size() == 1, "Expected exactly one table");

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, tableName);
        List<ConnectorTableLayoutResult> tableLayouts = metadata.getTableLayouts(SESSION, tableHandle, Constraint.alwaysTrue(), Optional.empty());
        assertTrue(tableLayouts.size() == 1, "Expected exactly one layout.");
        ConnectorTableLayout tableLayout = tableLayouts.get(0).getTableLayout();
        ConnectorTableLayoutHandle tableLayoutHandle = tableLayout.getHandle();
        assertTrue(tableLayoutHandle instanceof MemoryTableLayoutHandle);
        assertTrue(((MemoryTableLayoutHandle) tableLayoutHandle).getDataFragments().isEmpty(), "Data fragments should be empty");

        metadata.finishCreateTable(SESSION, table, ImmutableList.of());
    }

    @Test
    public void testCreateSchema()
    {
        assertEquals(metadata.listSchemaNames(SESSION), ImmutableList.of("default"));
        metadata.createSchema(SESSION, "test", ImmutableMap.of());
        assertEquals(metadata.listSchemaNames(SESSION), ImmutableList.of("default", "test"));
        assertEquals(metadata.listTables(SESSION, "test"), ImmutableList.of());

        SchemaTableName tableName = new SchemaTableName("test", "first_table");
        metadata.createTable(
                SESSION,
                new ConnectorTableMetadata(
                        tableName,
                        ImmutableList.of(),
                        ImmutableMap.of()));

        assertEquals(metadata.listTables(SESSION, null), ImmutableList.of(tableName));
        assertEquals(metadata.listTables(SESSION, "test"), ImmutableList.of(tableName));
        assertEquals(metadata.listTables(SESSION, "default"), ImmutableList.of());
    }

    private void assertNoTables()
    {
        assertEquals(metadata.listTables(SESSION, null), ImmutableList.of(), "No table was expected");
    }
}
