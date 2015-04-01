package org.sagebionetworks.bridge.synapse;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import org.joda.time.DateTime;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.table.ColumnType;

import org.sagebionetworks.bridge.exporter.BridgeExporterConfig;
import org.sagebionetworks.bridge.s3.S3Helper;
import org.sagebionetworks.bridge.util.BridgeExporterUtil;

public class SynapseHelper {
    public static final Map<String, ColumnType> BRIDGE_TYPE_TO_SYNAPSE_TYPE =
            ImmutableMap.<String, ColumnType>builder()
                    .put("attachment_blob", ColumnType.FILEHANDLEID)
                    .put("attachment_csv", ColumnType.FILEHANDLEID)
                    .put("attachment_json_blob", ColumnType.FILEHANDLEID)
                    .put("attachment_json_table", ColumnType.FILEHANDLEID)
                    .put("boolean", ColumnType.BOOLEAN)
                    .put("calendar_date", ColumnType.STRING)
                    .put("float", ColumnType.DOUBLE)
                    .put("inline_json_blob", ColumnType.STRING)
                    .put("int", ColumnType.INTEGER)
                    .put("string", ColumnType.STRING)
                    .put("timestamp", ColumnType.DATE)
                    .build();

    public static final Map<String, Integer> BRIDGE_TYPE_TO_MAX_LENGTH = ImmutableMap.<String, Integer>builder()
            .put("calendar_date", 10)
            .put("inline_json_blob", 1000)
            .put("string", 1000)
            .build();

    private BridgeExporterConfig bridgeExporterConfig;
    private S3Helper s3Helper;
    private SynapseClient synapseClient;

    public void setBridgeExporterConfig(BridgeExporterConfig bridgeExporterConfig) {
        this.bridgeExporterConfig = bridgeExporterConfig;
    }

    public void setS3Helper(S3Helper s3Helper) {
        this.s3Helper = s3Helper;
    }

    public void setSynapseClient(SynapseClient synapseClient) {
        this.synapseClient = synapseClient;
    }

    public String serializeToSynapseType(String studyId, String recordId, String fieldName, String bridgeType,
            JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        ColumnType synapseType = BRIDGE_TYPE_TO_SYNAPSE_TYPE.get(bridgeType);
        if (synapseType == null) {
            System.out.println("No Synapse type found for Bridge type " + bridgeType + ", record ID " + recordId);
            synapseType = ColumnType.STRING;
        }

        switch (synapseType) {
            case BOOLEAN:
                if (node.isBoolean()) {
                    return String.valueOf(node.booleanValue());
                }
                return null;
            case DATE:
                // date is a long epoch millis
                if (node.isTextual()) {
                    try {
                        DateTime dateTime = DateTime.parse(node.textValue());
                        return String.valueOf(dateTime.getMillis());
                    } catch (RuntimeException ex) {
                        // throw out malformatted dates
                        return null;
                    }
                } else if (node.isNumber()) {
                    return String.valueOf(node.longValue());
                }
                return null;
            case DOUBLE:
                // double only has double precision, not BigDecimal precision
                if (node.isNumber()) {
                    return String.valueOf(node.doubleValue());
                }
                return null;
            case FILEHANDLEID:
                // file handles are text nodes, where the text is the attachment ID (which is the S3 Key)
                if (node.isTextual()) {
                    String s3Key = node.textValue();
                    try {
                        return uploadFromS3ToSynapseFileHandle(studyId, fieldName, s3Key);
                    } catch (IOException | SynapseException ex) {
                        System.out.println("Error uploading attachment to Synapse for record ID " + recordId +
                                ", s3Key " + s3Key + ": " + ex.getMessage());
                        return null;
                    }
                }
                return null;
            case INTEGER:
                // int includes long
                if (node.isNumber()) {
                    return String.valueOf(node.longValue());
                }
                return null;
            case STRING:
                // String includes calendar_date (as JSON string) and inline_json_blob (arbitrary JSON)
                String nodeValue;
                if ("inline_json_blob".equalsIgnoreCase(bridgeType)) {
                    nodeValue = node.toString();
                } else {
                    nodeValue = node.textValue();
                }

                Integer maxLength = BRIDGE_TYPE_TO_MAX_LENGTH.get(bridgeType);
                if (maxLength == null) {
                    System.out.println("No max length found for Bridge type " + bridgeType);
                    maxLength = 1000;
                }
                return BridgeExporterUtil.trimToLengthAndWarn(nodeValue, maxLength, recordId);
            default:
                System.out.println("Unexpected Synapse Type " + String.valueOf(synapseType) + " for record ID "
                        + recordId);
                return null;
        }
    }

    private String uploadFromS3ToSynapseFileHandle(String studyId, String fieldName, String s3Key) throws IOException,
            SynapseException {
        // create temp file using the field name and s3Key as the prefix and the default suffix (null)
        File tempFile = File.createTempFile(fieldName + "-" + s3Key, null);

        try {
            // download from S3
            // TODO: update S3Helper to stream directly to a file instead of holding it in memory first
            byte[] fileBytes = s3Helper.readS3FileAsBytes(BridgeExporterUtil.S3_BUCKET_ATTACHMENTS, s3Key);
            Files.write(fileBytes, tempFile);

            // upload to Synapse
            FileHandle synapseFileHandle = synapseClient.createFileHandle(tempFile, "application/octet-stream",
                    bridgeExporterConfig.getProjectIdsByStudy().get(studyId));
            return synapseFileHandle.getId();
        } finally {
            // delete temp file
            tempFile.delete();
        }
    }
}
