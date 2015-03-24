package org.sagebionetworks.bridge.exporter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Index;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.s3.AmazonS3Client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import org.joda.time.LocalDate;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;

import org.sagebionetworks.bridge.s3.S3Helper;

public class BridgeExporter {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String S3_BUCKET_ATTACHMENTS = "org-sagebridge-attachment-prod";

    public static void main(String[] args) throws IOException {
        try {
            BridgeExporter bridgeExporter = new BridgeExporter();
            bridgeExporter.setDate(args[0]);
            bridgeExporter.run();
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            System.exit(0);
        }
    }

    private final Map<String, Integer> counterMap = new HashMap<>();
    private final Map<String, Set<String>> setCounterMap = new HashMap<>();

    private DynamoDB ddbClient;
    //private DynamoDbHelper ddbHelper;
    //private TransferManager s3TransferManager;
    private LocalDate date;
    private String dateString;
    private UploadSchemaHelper schemaHelper;
    private S3Helper s3Helper;

    public void run() throws IOException, ExecutionException, SynapseException, InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();

        init();
        downloadHealthDataRecords();

        stopwatch.stop();
        System.out.println("Took " + stopwatch.elapsed(TimeUnit.SECONDS) + " seconds");
    }

    public void setDate(String dateString) {
        this.dateString = dateString;
        this.date = LocalDate.parse(dateString);
    }

    private void init() throws IOException {
        // Dynamo DB client - move to Spring
        // This gets credentials from the default credential chain. For developer desktops, this is ~/.aws/credentials.
        // For EC2 instances, this happens transparently.
        // See http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/credentials.html and
        // http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/java-dg-setup.html#set-up-creds for more
        // info.
        ddbClient = new DynamoDB(new AmazonDynamoDBClient());
        //ddbHelper = new DynamoDbHelper(ddbClient);

        // S3 client - move to Spring
        AmazonS3Client s3Client = new AmazonS3Client();
        s3Helper = new S3Helper();
        s3Helper.setS3Client(s3Client);

        Table uploadSchemaTable = ddbClient.getTable("prod-heroku-UploadSchema");
        Table synapseTablesTable = ddbClient.getTable("prod-exporter-SynapseTables");
        schemaHelper = new UploadSchemaHelper();
        schemaHelper.setSchemaTable(uploadSchemaTable);
        schemaHelper.setSynapseTablesTable(synapseTablesTable);
        schemaHelper.init();
    }

    private void downloadHealthDataRecords()
            throws ExecutionException, IOException, SynapseException, InterruptedException {
        // get key objects by querying uploadDate index
        Table recordTable = ddbClient.getTable("prod-heroku-HealthDataRecord3");
        Index recordTableUploadDateIndex = recordTable.getIndex("uploadDate-index");
        Iterable<Item> recordKeyIter = recordTableUploadDateIndex.query("uploadDate", dateString);

        // re-query table to get values
        Set<String> schemasNotFound = new TreeSet<>();
        Multimap<String, String> appVersionsByStudy = TreeMultimap.create();
        for (Item oneRecordKey : recordKeyIter) {
            int numTotal = incrementCounter("numTotal");
            if (numTotal % 100 == 0) {
                System.out.println("Saw " + numTotal + " files so far...");
            }
            if (numTotal > 100) {
                break;
            }

            Item oneRecord = recordTable.getItem("id", oneRecordKey.get("id"));

            String userSharingScope = oneRecord.getString("userSharingScope");
            if (Strings.isNullOrEmpty(userSharingScope) || userSharingScope.equalsIgnoreCase("no_sharing")) {
                // must not be exported
                incrementCounter("numNotShared");
                continue;
            } else if (userSharingScope.equalsIgnoreCase("sponsors_and_partners")) {
                incrementCounter("numSharingBroadly");
            } else if (userSharingScope.equalsIgnoreCase("all_qualified_researchers")) {
                incrementCounter("numSharingSparsely");
            } else {
                System.out.println("Unknown sharing scope: " + userSharingScope);
            }

            String recordId = oneRecord.getString("id");
            String studyId = oneRecord.getString("studyId");
            String schemaId = oneRecord.getString("schemaId");
            int schemaRev = oneRecord.getInt("schemaRevision");

            String healthCode = oneRecord.getString("healthCode");
            incrementSetCounter("uniqueHealthCodes[" + studyId + "]", healthCode);

            JsonNode dataJson;
            if ("ios-survey".equals(schemaId)) {
                incrementCounter("numSurveys");

                // TODO: move survey translation layer to server-side
                JsonNode oldDataJson = JSON_MAPPER.readTree(oneRecord.getString("data"));
                JsonNode itemNode = oldDataJson.get("item");
                if (itemNode == null) {
                    System.out.println("Survey with no item for record ID " + recordId);
                    continue;
                }
                String item = itemNode.textValue();
                if (Strings.isNullOrEmpty(item)) {
                    System.out.println("Survey with null or empty item for record ID " + recordId);
                    continue;
                }
                schemaId = item;

                // surveys default to rev 1 until this code is moved to server side
                schemaRev = 1;

                // download answers from S3 attachments
                JsonNode answerLinkNode = oldDataJson.get("answers");
                if (answerLinkNode == null) {
                    System.out.println("Survey with no answer link for record ID " + recordId);
                    continue;
                }
                String answerLink = answerLinkNode.textValue();
                String answerText = s3Helper.readS3FileAsString(S3_BUCKET_ATTACHMENTS, answerLink);

                JsonNode answerArrayNode = JSON_MAPPER.readTree(answerText);
                ObjectNode convertedSurveyNode = JSON_MAPPER.createObjectNode();
                if (answerArrayNode == null) {
                    System.out.println("Survey with no answers for record ID " + recordId);
                    continue;
                }

                // copy fields to "non-survey" format
                int numAnswers = answerArrayNode.size();
                for (int i = 0; i < numAnswers; i++) {
                    JsonNode oneAnswerNode = answerArrayNode.get(i);

                    // question name ("item")
                    JsonNode answerItemNode = oneAnswerNode.get("item");
                    if (answerItemNode == null) {
                        System.out.println("Survey record ID " + recordId + " answer " + i
                                + " has no question name (item)");
                        continue;
                    }
                    String answerItem = answerItemNode.asText();
                    if (Strings.isNullOrEmpty(answerItem)) {
                        System.out.println("Survey record ID " + recordId + " answer " + i
                                + " has null or empty question name (item)");
                        continue;
                    }

                    // question type
                    JsonNode questionTypeNameNode = oneAnswerNode.get("questionTypeName");
                    if (questionTypeNameNode == null) {
                        // fall back to questionType
                        questionTypeNameNode = oneAnswerNode.get("questionType");
                    }
                    if (questionTypeNameNode == null) {
                        System.out.println("Survey record ID " + recordId + " answer " + i + " has no question type");
                        continue;
                    }
                    String questionTypeName = questionTypeNameNode.asText();
                    if (Strings.isNullOrEmpty(questionTypeName)) {
                        System.out.println("Survey record ID " + recordId + " answer " + i
                                + " has null or empty question type");
                        continue;
                    }

                    // answer
                    // TODO: Hey, this should really be a Map<String, String>, not a big switch statement
                    JsonNode answerAnswerNode = null;
                    switch (questionTypeName) {
                        case "Boolean":
                            answerAnswerNode = oneAnswerNode.get("booleanAnswer");
                            break;
                        case "Date":
                            answerAnswerNode = oneAnswerNode.get("dateAnswer");
                            break;
                        case "Decimal":
                        case "Integer":
                            answerAnswerNode = oneAnswerNode.get("numericAnswer");
                            break;
                        case "MultipleChoice":
                        case "SingleChoice":
                            answerAnswerNode = oneAnswerNode.get("choiceAnswers");
                            break;
                        case "None":
                        case "Scale":
                            // yes, None really gets the answer from scaleAnswer
                            answerAnswerNode = oneAnswerNode.get("scaleAnswer");
                            break;
                        case "Text":
                            answerAnswerNode = oneAnswerNode.get("textAnswer");
                            break;
                        case "TimeInterval":
                            answerAnswerNode = oneAnswerNode.get("intervalAnswer");
                            break;
                        case "TimeOfDay":
                            answerAnswerNode = oneAnswerNode.get("dateComponentsAnswer");
                            break;
                        default:
                            System.out.println("Survey record ID " + recordId + " answer " + i
                                    + " has unknown question type " + questionTypeName);
                            break;
                    }
                    convertedSurveyNode.set(answerItem, answerAnswerNode);

                    // if there's a unit, add it as well
                    JsonNode unitNode = oneAnswerNode.get("unit");
                    if (unitNode != null && !unitNode.isNull()) {
                        convertedSurveyNode.set(answerItem + "_unit", unitNode);
                    }

                    // TODO: attachment types
                }

                dataJson = convertedSurveyNode;
            } else {
                incrementCounter("numNonSurveys");
                dataJson = JSON_MAPPER.readTree(oneRecord.getString("data"));
            }

            UploadSchemaKey schemaKey = new UploadSchemaKey(studyId, schemaId, schemaRev);
            Item schema = schemaHelper.getSchema(schemaKey);
            if (schema == null) {
                // No schema. Skip.
                System.out.println("Schema " + schemaKey.toString() + " not found for record " + recordId);
                schemasNotFound.add(schemaKey.toString());
                continue;
            }

            // get phone and app info
            String metadataString = oneRecord.getString("metadata");
            JsonNode metadataJson = !Strings.isNullOrEmpty(metadataString) ? JSON_MAPPER.readTree(metadataString)
                    : null;
            String appVersion = trimToLengthAndWarn(removeTabs(getJsonString(metadataJson, "appVersion")), 48);
            String phoneInfo = trimToLengthAndWarn(removeTabs(getJsonString(metadataJson, "phoneInfo")), 48);

            // app version bookkeeping
            appVersionsByStudy.put(studyId, appVersion);

            // write record
            String synapseTableId = schemaHelper.getSynapseTableId(schemaKey);
            List<SelectColumn> headerList = schemaHelper.getSynapseAppendRowHeaders(synapseTableId);
            List<String> rowValueList = new ArrayList<>();

            // common values
            rowValueList.add(recordId);
            rowValueList.add(healthCode);
            rowValueList.add(dateString);

            // createdOn as a long epoch millis
            rowValueList.add(String.valueOf(oneRecord.getLong("createdOn")));

            // TODO: metadata
            rowValueList.add(null);

            rowValueList.add(appVersion);
            rowValueList.add(phoneInfo);

            // schema-specific columns
            JsonNode fieldDefList = JSON_MAPPER.readTree(schema.getString("fieldDefinitions"));
            for (JsonNode oneFieldDef : fieldDefList) {
                // TODO
                // The following fields in the following schemas need special casing to convert from strings to
                // attachments
                // Breast Cancer
                // * Daily Journal
                //   * content_data.APHMoodLogNoteText
                //   * DailyJournalStep103_data.content
                // * Exercise Journal
                //   * exercisesurvey101_data.result through exercisesurvey106_data.result

                String name = oneFieldDef.get("name").textValue();
                String bridgeType = oneFieldDef.get("type").textValue().toLowerCase();
                JsonNode valueNode = dataJson.get(name);
                String value = schemaHelper.serializeToSynapseType(bridgeType, valueNode);
                rowValueList.add(value);
            }

            Row row = new Row();
            row.setValues(rowValueList);

            // row set
            // TODO: batch rows?
            RowSet rowSet = new RowSet();
            rowSet.setHeaders(headerList);
            rowSet.setRows(Collections.singletonList(row));
            rowSet.setTableId(synapseTableId);

            // call Synapse
            schemaHelper.appendSynapseRows(rowSet, synapseTableId);

            // TODO: PhoneAppInfo table
        }

        for (Map.Entry<String, Integer> oneCounter : counterMap.entrySet()) {
            System.out.println(oneCounter.getKey() + ": " + oneCounter.getValue());
        }
        for (Map.Entry<String, Set<String>> oneSetCounter : setCounterMap.entrySet()) {
            System.out.println(oneSetCounter.getKey() + ": " + oneSetCounter.getValue().size());
        }
        if (!schemasNotFound.isEmpty()) {
            System.out.println("The following schemas were referenced but not found: "
                    + Joiner.on(", ").join(schemasNotFound));
        }
        for (Map.Entry<String, Collection<String>> appVersionEntry : appVersionsByStudy.asMap().entrySet()) {
            System.out.println("App versions for " + appVersionEntry.getKey() + ": "
                    + Joiner.on(", ").join(appVersionEntry.getValue()));
        }
    }

    private int incrementCounter(String name) {
        Integer oldValue = counterMap.get(name);
        int newValue;
        if (oldValue == null) {
            newValue = 1;
        } else {
            newValue = oldValue + 1;
        }

        counterMap.put(name, newValue);
        return newValue;
    }

    // Only increments the counter if the value hasn't already been used. Used for things like counting unique health
    // codes.
    private void incrementSetCounter(String name, String value) {
        Set<String> set = setCounterMap.get(name);
        if (set == null) {
            set = new HashSet<>();
            setCounterMap.put(name, set);
        }
        set.add(value);
    }

    private static String getJsonString(JsonNode node, String key) {
        if (node.hasNonNull(key)) {
            return node.get(key).textValue();
        } else {
            return null;
        }
    }

    private static String removeTabs(String in) {
        if (in != null) {
            return in.replaceAll("\t+", " ");
        } else {
            return null;
        }
    }

    public static String trimToLengthAndWarn(String in, int maxLength) {
        if (in != null && in.length() > maxLength) {
            System.out.println("Trunacting string " + in + " to length " + maxLength);
            return in.substring(0, maxLength);
        } else {
            return in;
        }
    }
}
