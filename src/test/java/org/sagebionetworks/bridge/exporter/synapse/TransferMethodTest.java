package org.sagebionetworks.bridge.exporter.synapse;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.google.common.collect.ImmutableMap;

import org.sagebionetworks.repo.model.table.ColumnType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test for transfer() in TransferMethod
 */
public class TransferMethodTest {

    @Test
    public void testTransfer() {
        final String testIntName = "test_int";
        final String testStringName = "test_string";
        final String testStringSetName = "test_string_set";
        final String testDateName = "test_date";
        final String testMap = "test_map";

        // create mock record
        Item testRecord = new Item();
        testRecord.withInt(testIntName, 42);
        testRecord.withString(testStringName, "test_string_value");
        testRecord.withStringSet(testStringSetName, "test_string_set_value_1", "test_string_set_value_2");
        testRecord.withLong(testDateName, 1484181511);
        testRecord.withString("largetext", "This is a small largetext");
        testRecord.withMap(testMap, ImmutableMap.of("subA", "extA", "subB", ""));

        // verify
        assertEquals(TransferMethod.INT.transfer(testIntName, testRecord), "42");
        assertEquals(TransferMethod.STRING.transfer(testStringName, testRecord), "test_string_value");
        assertEquals(TransferMethod.STRINGSET.transfer(testStringSetName, testRecord), "test_string_set_value_1,test_string_set_value_2");
        assertEquals(TransferMethod.DATE.transfer(testDateName, testRecord), "1484181511");
        assertEquals(TransferMethod.LARGETEXT.transfer("largetext", testRecord), "This is a small largetext");
        assertEquals(TransferMethod.STRINGMAP.transfer(testMap, testRecord), "|subA=extA|subB=|");
    }

    // branch coverage
    @Test
    public void transferStringSetWithNullValue() {
        Item emptyRecord = new Item();
        assertEquals(TransferMethod.STRINGSET.transfer("no-value", emptyRecord), "");
    }

    @Test
    public void testGetColumnType() {
        assertEquals(TransferMethod.INT.getColumnType(), ColumnType.INTEGER);
        assertEquals(TransferMethod.STRING.getColumnType(), ColumnType.STRING);
        assertEquals(TransferMethod.STRINGSET.getColumnType(), ColumnType.STRING);
        assertEquals(TransferMethod.DATE.getColumnType(), ColumnType.DATE);
        assertEquals(TransferMethod.LARGETEXT.getColumnType(), ColumnType.LARGETEXT);
        assertEquals(TransferMethod.STRINGMAP.getColumnType(), ColumnType.STRING);
    }
    
    @Test
    public void testTransferStringMap() {
        Map<String,String> map = new LinkedHashMap<>();
        map.put("substudyA", "");
        map.put("substudyB", "externalIdB");
        
        Item testRecord = new Item();
        testRecord.withMap("testStringName", map);
        
        String output = TransferMethod.STRINGMAP.transfer("testStringName", testRecord);
        assertEquals("|substudyA=|substudyB=externalIdB|", output);
    }
    
    @Test
    public void testTransferStringMapWithNullValue() {
        Item testRecord = new Item();
        
        assertNull(TransferMethod.STRINGMAP.transfer("testStringName", testRecord));
    }
    
    @Test
    public void testTransferStringMapWithEmptyValue() {
        Item testRecord = new Item();
        testRecord.withMap("testStringName", ImmutableMap.of());
        
        assertNull(TransferMethod.STRINGMAP.transfer("testStringName", testRecord));
    }
}    
