package org.sagebionetworks.bridge.worker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;

import org.sagebionetworks.bridge.exceptions.BridgeExporterException;
import org.sagebionetworks.bridge.util.BridgeExporterUtil;

/** Synapse export worker for app version tables. */
public class AppVersionExportHandler extends SynapseExportHandler {
    private static final Joiner APP_VERSION_JOINER = Joiner.on("; ");
    private final Set<String> uniqueAppVersionSet = new TreeSet<>();

    @Override
    protected void initSchemas() {
        // noop
    }

    @Override
    protected String getDdbTableName() {
        return "SynapseMetaTables";
    }

    @Override
    protected String getDdbTableKeyName() {
        return "tableName";
    }

    @Override
    protected List<ColumnModel> getSynapseTableColumnList() {
        List<ColumnModel> columnList = new ArrayList<>();

        ColumnModel recordIdColumn = new ColumnModel();
        recordIdColumn.setName("recordId");
        recordIdColumn.setColumnType(ColumnType.STRING);
        recordIdColumn.setMaximumSize(36L);
        columnList.add(recordIdColumn);

        ColumnModel healthCodeColumn = new ColumnModel();
        healthCodeColumn.setName("healthCode");
        healthCodeColumn.setColumnType(ColumnType.STRING);
        healthCodeColumn.setMaximumSize(36L);
        columnList.add(healthCodeColumn);

        ColumnModel externalIdColumn = new ColumnModel();
        externalIdColumn.setName("externalId");
        externalIdColumn.setColumnType(ColumnType.STRING);
        externalIdColumn.setMaximumSize(128L);
        columnList.add(externalIdColumn);

        ColumnModel originalTableColumn = new ColumnModel();
        originalTableColumn.setName("originalTable");
        originalTableColumn.setColumnType(ColumnType.STRING);
        originalTableColumn.setMaximumSize(128L);
        columnList.add(originalTableColumn);

        ColumnModel appVersionColumn = new ColumnModel();
        appVersionColumn.setName("appVersion");
        appVersionColumn.setColumnType(ColumnType.STRING);
        appVersionColumn.setMaximumSize(48L);
        columnList.add(appVersionColumn);

        ColumnModel phoneInfoColumn = new ColumnModel();
        phoneInfoColumn.setName("phoneInfo");
        phoneInfoColumn.setColumnType(ColumnType.STRING);
        phoneInfoColumn.setMaximumSize(48L);
        columnList.add(phoneInfoColumn);

        return columnList;
    }

    @Override
    protected String getSynapseTableName() {
        return getStudyId() + "-appVersion";
    }

    @Override
    protected List<String> getTsvHeaderList() {
        List<String> fieldNameList = new ArrayList<>();
        fieldNameList.add("recordId");
        fieldNameList.add("healthCode");
        fieldNameList.add("externalId");
        fieldNameList.add("originalTable");
        fieldNameList.add("appVersion");
        fieldNameList.add("phoneInfo");
        return fieldNameList;
    }

    @Override
    protected List<String> getTsvRowValueList(ExportTask task) throws BridgeExporterException {
        Item record = task.getRecord();
        if (record == null) {
            throw new BridgeExporterException("Null record for AppVersionExportWorker");
        }
        String recordId = record.getString("id");

        // get phone and app info
        String appVersion = null;
        String phoneInfo = null;
        String metadataString = record.getString("metadata");
        if (!Strings.isNullOrEmpty(metadataString)) {
            try {
                JsonNode metadataJson = BridgeExporterUtil.JSON_MAPPER.readTree(metadataString);
                appVersion = BridgeExporterUtil.getJsonStringRemoveTabsAndTrim(metadataJson, "appVersion", 48,
                        recordId);
                phoneInfo = BridgeExporterUtil.getJsonStringRemoveTabsAndTrim(metadataJson, "phoneInfo", 48, recordId);
            } catch (IOException ex) {
                // we can recover from this
                System.out.println("[ERROR] Error parsing metadata for record ID " + recordId + ": "
                        + ex.getMessage());
            }
        }

        // construct row
        List<String> rowValueList = new ArrayList<>();
        rowValueList.add(recordId);
        rowValueList.add(record.getString("healthCode"));
        rowValueList.add(BridgeExporterUtil.getDdbStringRemoveTabsAndTrim(record, "userExternalId", 128, recordId));
        rowValueList.add(task.getSchemaKey().toString());
        rowValueList.add(appVersion);
        rowValueList.add(phoneInfo);

        // book keeping
        if (!Strings.isNullOrEmpty(appVersion)) {
            uniqueAppVersionSet.add(appVersion);
        }

        return rowValueList;
    }

    @Override
    public void reportMetrics() {
        super.reportMetrics();

        if (!uniqueAppVersionSet.isEmpty()) {
            System.out.println("[METRICS] Unique app versions for study " + getStudyId() + ": "
                    + APP_VERSION_JOINER.join(uniqueAppVersionSet));
        }
    }
}
