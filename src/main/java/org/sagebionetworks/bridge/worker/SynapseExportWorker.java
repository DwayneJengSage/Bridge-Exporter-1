package org.sagebionetworks.bridge.worker;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.ResourceAccess;
import org.sagebionetworks.repo.model.file.FileHandle;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.CsvTableDescriptor;
import org.sagebionetworks.repo.model.table.TableEntity;
import org.sagebionetworks.repo.model.table.UploadToTableResult;

import org.sagebionetworks.bridge.exceptions.ExportWorkerException;
import org.sagebionetworks.bridge.exceptions.SchemaNotFoundException;
import org.sagebionetworks.bridge.util.BridgeExporterUtil;

/**
 * This is a worker thread who's solely responsible for a single table in Synapse (study, schema, rev). This thread
 * creates the table, if it doesn't exist, reads through a stream of DDB records to create a TSV, then uploads the
 * TSV to the Synapse table.
 */
public abstract class SynapseExportWorker extends ExportWorker {
    // Constants
    private static final Set<ACCESS_TYPE> ACCESS_TYPE_ALL = ImmutableSet.copyOf(ACCESS_TYPE.values());
    private static final Set<ACCESS_TYPE> ACCESS_TYPE_READ = ImmutableSet.of(ACCESS_TYPE.READ, ACCESS_TYPE.DOWNLOAD);
    private static final int ASYNC_UPLOAD_TIMEOUT_SECONDS = 300;
    private static final Joiner JOINER_COLUMN_SEPARATOR = Joiner.on('\t').useForNull("");

    // Internal state
    private int errorCount = 0;
    private int lineCount = 0;
    private String synapseTableId;
    private File tsvFile;
    private PrintWriter tsvWriter;

    @Override
    public void run() {
        try {
            try {
                workerLoop();
            } catch (RuntimeException ex) {
                System.out.println("[ERROR] RuntimeException running worker loop for table " + getSynapseTableName()
                        + ": " + ex.getMessage());
                ex.printStackTrace(System.out);
                return;
            }

            String tsvPath = tsvFile.getAbsolutePath();
            Stopwatch uploadTsvStopwatch = Stopwatch.createStarted();
            try {
                uploadTsvToSynapse();
            } catch (ExportWorkerException ex) {
                System.out.println("[ERROR] Error uploading file " + tsvPath + " to Synapse table "
                        + getSynapseTableName() + " with table ID " + synapseTableId + ": " +  ex.getMessage());
                ex.printStackTrace(System.out);
            } catch (RuntimeException ex) {
                System.out.println("[ERROR] RuntimeException uploading file " + tsvPath + " to Synapse table "
                        + getSynapseTableName() + " with table ID " + synapseTableId + ": " +  ex.getMessage());
                ex.printStackTrace(System.out);
            } finally {
                uploadTsvStopwatch.stop();
                long elapsedSec = uploadTsvStopwatch.elapsed(TimeUnit.SECONDS);

                if (lineCount > 0 && elapsedSec > 60) {
                    System.out.println("[METRICS] " + getSynapseTableName() + ".uploadTsvTime: " + elapsedSec
                            + " seconds");
                }
            }
        } catch (Throwable t) {
            System.out.println("[ERROR] Unknown error for table " + getSynapseTableName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
        } finally {
            if (lineCount > 0 || errorCount > 0) {
                System.out.println("[METRICS] Synapse worker " + getSynapseTableName() + " done: "
                        + BridgeExporterUtil.getCurrentLocalTimestamp());
            }
        }
    }

    /**
     * Initializes the worker. This includes initializing the schema (where applicable), initializing (and potentially
     * creating) the Synapse table, and initializing the TSV file and writer. This is done outside the run loop and
     * instead done sequentially by the main thread because concurrent create table requests in Synapse may get
     * throttled.
     */
    @Override
    public void init() throws ExportWorkerException, SchemaNotFoundException {
        initSchemas();
        initSynapseTable();
        initTsvWriter();
    }

    private void initSynapseTable() throws ExportWorkerException {
        // check DDB to see if the Synapse table exists
        Table synapseTableDdbTable = getManager().getDdbClient().getTable(
                getManager().getBridgeExporterConfig().getDdbPrefix() + getDdbTableName());
        try {
            Iterable<Item> tableIterable = synapseTableDdbTable.query(getDdbTableKeyName(), getSynapseTableName());
            Iterator<Item> tableIterator = tableIterable.iterator();
            if (tableIterator.hasNext()) {
                Item oneTable = tableIterator.next();
                synapseTableId = oneTable.getString("tableId");

                if (!Strings.isNullOrEmpty(synapseTableId)) {
                    return;
                }
            }
        } catch (AmazonClientException ex) {
            throw new ExportWorkerException("Error calling DDB for Synapse Table: " + ex.getMessage(), ex);
        }

        // Synapse table doesn't exist. Create it.
        // Create columns
        List<ColumnModel> columnList = getSynapseTableColumnList();
        List<ColumnModel> createdColumnList;
        try {
            createdColumnList = getManager().getSynapseHelper().createColumnModelsWithRetry(columnList);
        } catch (SynapseException ex) {
            throw new ExportWorkerException("Error creating Synapse column models: " + ex.getMessage(), ex);
        }
        if (columnList.size() != createdColumnList.size()) {
            throw new ExportWorkerException("Error creating Synapse table " + getSynapseTableName()
                    + ": Tried to create " + columnList.size() + " columns. Actual: " + createdColumnList.size()
                    + " columns.");
        }

        List<String> columnIdList = new ArrayList<>();
        for (ColumnModel oneCreatedColumn : createdColumnList) {
            columnIdList.add(oneCreatedColumn.getId());
        }

        // create table
        TableEntity synapseTable = new TableEntity();
        synapseTable.setName(getSynapseTableName());
        synapseTable.setParentId(getProjectId());
        synapseTable.setColumnIds(columnIdList);
        TableEntity createdTable;
        try {
            createdTable = getManager().getSynapseHelper().createTableWithRetry(synapseTable);
        } catch (SynapseException ex) {
            throw new ExportWorkerException("Error creating Synapse table: " + ex.getMessage(), ex);
        }
        synapseTableId = createdTable.getId();

        // create ACLs
        Set<ResourceAccess> resourceAccessSet = new HashSet<>();

        ResourceAccess exporterOwnerAccess = new ResourceAccess();
        exporterOwnerAccess.setPrincipalId(getManager().getBridgeExporterConfig().getPrincipalId());
        exporterOwnerAccess.setAccessType(ACCESS_TYPE_ALL);
        resourceAccessSet.add(exporterOwnerAccess);

        ResourceAccess dataAccessTeamAccess = new ResourceAccess();
        dataAccessTeamAccess.setPrincipalId(getDataAccessTeamId());
        dataAccessTeamAccess.setAccessType(ACCESS_TYPE_READ);
        resourceAccessSet.add(dataAccessTeamAccess);

        AccessControlList acl = new AccessControlList();
        acl.setId(synapseTableId);
        acl.setResourceAccess(resourceAccessSet);

        try {
            getManager().getSynapseHelper().createAclWithRetry(acl);
        } catch (SynapseException ex) {
            throw new ExportWorkerException("Error setting ACLs on Synapse table " + synapseTableId + ": "
                    + ex.getMessage(), ex);
        }

        // write back to DDB table
        Item synapseTableDdbItem = new Item();
        synapseTableDdbItem.withString(getDdbTableKeyName(), getSynapseTableName());
        synapseTableDdbItem.withString("tableId", synapseTableId);
        try {
            synapseTableDdbTable.putItem(synapseTableDdbItem);
        } catch (AmazonClientException ex) {
            throw new ExportWorkerException("Error writing table ID back to DDB: " + ex.getMessage(), ex);
        }
    }

    private void initTsvWriter() throws ExportWorkerException {
        // file
        try {
            tsvFile = File.createTempFile(getSynapseTableName() + ".", ".tsv");
        } catch (IOException ex) {
            throw new ExportWorkerException("Error creating temp TSV file: " + ex.getMessage(), ex);
        }

        // writer
        try {
        OutputStream stream = new FileOutputStream(tsvFile);
        tsvWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(stream, Charsets.UTF_8)));
        } catch (IOException ex) {
            throw new ExportWorkerException("Error creating Stream and PrintWriter to TSV file: " + ex.getMessage(),
                    ex);
        }

        // write TSV headers
        List<String> tsvHeaderList = getTsvHeaderList();
        tsvWriter.println(JOINER_COLUMN_SEPARATOR.join(tsvHeaderList));
    }

    private void workerLoop() {
        while (true) {
            // take waits for an element to become available
            ExportTask task;
            try {
                task = takeTask();
            } catch (InterruptedException ex) {
                // noop
                continue;
            }

            Item record = task.getRecord();
            String recordId = record != null ? record.getString("id") : null;

            try {
                switch (task.getType()) {
                    case PROCESS_RECORD:
                    case PROCESS_IOS_SURVEY:
                        appendToTsv(task);
                        lineCount++;
                        break;
                    case END_OF_STREAM:
                        // END_OF_STREAM means we're finished
                        return;
                    default:
                        errorCount++;
                        System.out.println("[ERROR] Unknown task type " + task.getType().name() + " for record "
                                + recordId + " table " + getSynapseTableName());
                        break;
                }
            } catch (ExportWorkerException ex) {
                errorCount++;
                System.out.println("[ERROR] Error processing record " + recordId + " for table "
                        + getSynapseTableName() + ": " + ex.getMessage());
            } catch (RuntimeException ex) {
                errorCount++;
                System.out.println("[ERROR] RuntimeException processing record " + recordId + " for table "
                        + getSynapseTableName() + ": " + ex.getMessage());
                ex.printStackTrace(System.out);
            }
        }
    }

    private void appendToTsv(ExportTask task) throws ExportWorkerException {
        List<String> rowValueList = getTsvRowValueList(task);
        tsvWriter.println(JOINER_COLUMN_SEPARATOR.join(rowValueList));
    }

    private void uploadTsvToSynapse() throws ExportWorkerException {
        // flush and close writer, check for errors
        tsvWriter.flush();
        if (tsvWriter.checkError()) {
            throw new ExportWorkerException("TSV writer has unknown error");
        }
        tsvWriter.close();

        // filter on line count
        if (lineCount == 0) {
            // delete files with no lines--they're useless anyway
            tsvFile.delete();
            return;
        }

        // upload file to synapse as a file handle
        FileHandle tableFileHandle;
        try {
            tableFileHandle = getManager().getSynapseHelper().createFileHandleWithRetry(tsvFile,
                    "text/tab-separated-values", getProjectId());
        } catch (IOException | SynapseException ex) {
            System.out.println("[re-upload-tsv] file " + tsvFile.getAbsolutePath() + " " + synapseTableId);
            throw new ExportWorkerException("Error uploading TSV as a file handle: " + ex.getMessage());
        }
        String fileHandleId = tableFileHandle.getId();

        // We've successfully uploaded the file. We can delete the file now.
        tsvFile.delete();

        // start tsv import
        CsvTableDescriptor tableDesc = new CsvTableDescriptor();
        tableDesc.setIsFirstLineHeader(true);
        tableDesc.setSeparator("\t");

        String jobToken;
        try {
            jobToken = getManager().getSynapseHelper().uploadTsvStartWithRetry(synapseTableId, fileHandleId,
                    tableDesc);
        } catch (SynapseException ex) {
            System.out.println("[re-upload-tsv] filehandle " + fileHandleId + " " + synapseTableId);
            throw new ExportWorkerException("Error starting async import of file handle " + fileHandleId + ": "
                    + ex.getMessage(), ex);
        }

        // poll asyncGet until success or timeout
        boolean success = false;
        for (int sec = 0; sec < ASYNC_UPLOAD_TIMEOUT_SECONDS; sec++) {
            // sleep for 1 sec
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                // noop
            }

            // poll
            try {
                UploadToTableResult uploadResult = getManager().getSynapseHelper().getUploadTsvStatus(jobToken,
                        synapseTableId);
                if (uploadResult == null) {
                    // Result not ready. Sleep some more.
                    continue;
                }

                Long linesProcessed = uploadResult.getRowsProcessed();
                if (linesProcessed == null || linesProcessed != lineCount) {
                    throw new ExportWorkerException("Wrong number of lines processed importing file handle "
                            + fileHandleId + ", expected=" + lineCount + ", actual=" + linesProcessed);
                }

                success = true;
                break;
            } catch (SynapseResultNotReadyException ex) {
                // results not ready, sleep some more
            } catch (SynapseException ex) {
                System.out.println("[re-upload-tsv] filehandle " + fileHandleId + " " + synapseTableId);
                throw new ExportWorkerException("Error polling job status of importing file handle " + fileHandleId
                        + ": " + ex.getMessage(), ex);
            }
        }

        if (!success) {
            throw new ExportWorkerException("Timed out uploading file handle " + fileHandleId);
        }
    }

    @Override
    public void reportMetrics() {
        if (lineCount > 0) {
            System.out.println("[METRICS] " + getSynapseTableName() + ".lineCount: " + lineCount);
        }
        if (errorCount > 0) {
            System.out.println("[METRICS] " + getSynapseTableName() + ".errorCount: " + errorCount);
        }
    }

    /** Initialize schemas. Child classes need to override this. In some cases, this may be a noop. */
    protected abstract void initSchemas() throws SchemaNotFoundException;

    /** Table name (excluding prefix) of the DDB table that holds Synapse table IDs. */
    protected abstract String getDdbTableName();

    /** Hash key name of the DDB table that holds Synapse table IDs. */
    protected abstract String getDdbTableKeyName();

    /**
     * List of Synapse table column model objets, to be used to create both the column models and the Synapse table.
     */
    protected abstract List<ColumnModel> getSynapseTableColumnList();

    /**
     * Synapse table name. A table will be created in Synapse with this name if it doesn't already exist. This is also
     * used as the hash key value into the DDB table that holds the Synapse table ID.
     */
    protected abstract String getSynapseTableName();

    /** List of header names for TSV creation. This should match with Synapse table column names. */
    protected abstract List<String> getTsvHeaderList();

    /**
     * Creates a row values for a single row from the given export task. The export task shouldn't be an END_OF_STREAM
     * task.
     */
    protected abstract List<String> getTsvRowValueList(ExportTask task) throws ExportWorkerException;
}
