package org.sagebionetworks.bridge.exporter.request;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;

import org.sagebionetworks.bridge.json.DateTimeDeserializer;
import org.sagebionetworks.bridge.json.DateTimeToStringSerializer;
import org.sagebionetworks.bridge.schema.UploadSchemaKey;

/** Encapsulates a request to Bridge EX and the ability to serialize to/from JSON. */
@JsonDeserialize(builder = BridgeExporterRequest.Builder.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BridgeExporterRequest {
    static final String DEFAULT_TAG = "(no tag)";

    private final DateTime startDateTime;
    private final DateTime endDateTime;
    private final String exporterDdbPrefixOverride;
    private final String recordIdS3Override;
    private final int redriveCount;
    private final BridgeExporterSharingMode sharingMode;
    private final Set<String> studyWhitelist;
    private final Map<String, String> synapseProjectOverrideMap;
    private final Set<UploadSchemaKey> tableWhitelist;
    private final String tag;
    private final boolean useLastExportTime;

    /** Private constructor. To build, go through the builder. */
    private BridgeExporterRequest(DateTime startDateTime, DateTime endDateTime, String exporterDdbPrefixOverride,
            String recordIdS3Override, int redriveCount, BridgeExporterSharingMode sharingMode,
            Set<String> studyWhitelist, Map<String, String> synapseProjectOverrideMap,
            Set<UploadSchemaKey> tableWhitelist, String tag, boolean useLastExportTime) {
        this.startDateTime = startDateTime;
        this.endDateTime = endDateTime;
        this.exporterDdbPrefixOverride = exporterDdbPrefixOverride;
        this.recordIdS3Override = recordIdS3Override;
        this.redriveCount = redriveCount;
        this.sharingMode = sharingMode;
        this.studyWhitelist = studyWhitelist;
        this.synapseProjectOverrideMap = synapseProjectOverrideMap;
        this.tableWhitelist = tableWhitelist;
        this.tag = tag;
        this.useLastExportTime = useLastExportTime;
    }

    /** * Start date, inclusive. If this is specified, the useLastExportTime should be set to false. */
    @JsonSerialize(using = DateTimeToStringSerializer.class)
    public DateTime getStartDateTime() {
        return startDateTime;
    }

    /** End date, exclusive. For use with export jobs daily and hourly. */
    @JsonSerialize(using = DateTimeToStringSerializer.class)
    public DateTime getEndDateTime() {
        return endDateTime;
    }

    /**
     * Override for the prefix for DDB tables that keep track of Synapse tables. This is generally used for one-off
     * exports to separate Synapse projects. This is optional, but must be specified if synapseProjectOverrideMap is
     * also specified.
     */
    public String getExporterDdbPrefixOverride() {
        return exporterDdbPrefixOverride;
    }

    /**
     * Override to export a list of record IDs instead of querying DDB. This is generally used for redriving specific
     * records.
     */
    public String getRecordIdS3Override() {
        return recordIdS3Override;
    }

    /**
     * The number of times this request has been redriven. Zero if this is the first request. 1 if this is the first
     * redrive. And so forth.
     */
    public int getRedriveCount() {
        return redriveCount;
    }

    /**
     * Whether Bridge EX should export public data, shared data, or all data. This is generally used for one-off
     * exports to non-default projects with non-default sharing settings. This is optional and defaults to "shared" if
     * left blank.
     */
    public BridgeExporterSharingMode getSharingMode() {
        return sharingMode;
    }

    /**
     * Whitelist of studies that Bridge EX should export. This is optional and is generally used for one-off exports
     * for specific studies.
     */
    public Set<String> getStudyWhitelist() {
        return studyWhitelist;
    }

    /**
     * Override map for Synapse projects. Key is study ID. Value is Synapse project ID. This is generally used for
     * one-off exports to separate Synapse projects. This is optional, but must be specified if
     * exporterDdbPrefixOverride is also specified.
     */
    public Map<String, String> getSynapseProjectOverrideMap() {
        return synapseProjectOverrideMap;
    }

    /**
     * White list of tables (schemas) that Bridge EX should export. This is optional and is generally used for
     * redriving specific tables.
     */
    public Set<UploadSchemaKey> getTableWhitelist() {
        return tableWhitelist;
    }

    /**
     * Tag, used to trace specific requests based on their sources or based on their semantic intent. This is optional,
     * but strongly recommended.
     */
    public String getTag() {
        return tag;
    }

    /**
     * If true, we export since the study's last export time instead of using start date. If there is no last export
     * time, we export since the start of yesterday.
     */
    public boolean getUseLastExportTime() {
        return this.useLastExportTime;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BridgeExporterRequest)) {
            return false;
        }
        BridgeExporterRequest that = (BridgeExporterRequest) o;
        return Objects.equals(endDateTime, that.endDateTime) &&
                Objects.equals(exporterDdbPrefixOverride, that.exporterDdbPrefixOverride) &&
                Objects.equals(recordIdS3Override, that.recordIdS3Override) &&
                redriveCount == that.redriveCount &&
                sharingMode == that.sharingMode &&
                Objects.equals(studyWhitelist, that.studyWhitelist) &&
                Objects.equals(synapseProjectOverrideMap, that.synapseProjectOverrideMap) &&
                Objects.equals(tableWhitelist, that.tableWhitelist) &&
                Objects.equals(tag, that.tag) &&
                Objects.equals(useLastExportTime, that.useLastExportTime) &&
                Objects.equals(startDateTime, that.startDateTime);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(endDateTime, exporterDdbPrefixOverride, recordIdS3Override, redriveCount, sharingMode,
                studyWhitelist, synapseProjectOverrideMap, tableWhitelist, tag, useLastExportTime, startDateTime);
    }

    /**
     * Converts the request to a string for use in log messages. Only contains the tag and a basic parameter to
     * identify the record source.
     */
    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();

        if (startDateTime != null) {
            stringBuilder.append("startDateTime=");
            stringBuilder.append(startDateTime);
            stringBuilder.append(", ");
        }

        if (endDateTime != null) {
            stringBuilder.append("endDateTime=");
            stringBuilder.append(endDateTime);
        }
        if (recordIdS3Override != null){
            stringBuilder.append("recordIdS3Override=");
            stringBuilder.append(recordIdS3Override);
        }

        // Include redriveCount, since this is helpful for logging and diagnostics.
        stringBuilder.append(", redriveCount=");
        stringBuilder.append(redriveCount);

        // Always include tag.
        stringBuilder.append(", tag=");
        stringBuilder.append(tag);

        stringBuilder.append(", useLastExportTime=");
        stringBuilder.append(useLastExportTime);

        return stringBuilder.toString();
    }

    /** Request builder. */
    public static class Builder {
        private DateTime startDateTime;
        private DateTime endDateTime;
        private String exporterDdbPrefixOverride;
        private String recordIdS3Override;
        private int redriveCount;
        private BridgeExporterSharingMode sharingMode;
        private Set<String> studyWhitelist;
        private Map<String, String> synapseProjectOverrideMap;
        private Set<UploadSchemaKey> tableWhitelist;
        private String tag;
        private Boolean useLastExportTime;

        /** Sets the builder with a copy of the given request. */
        public Builder copyOf(BridgeExporterRequest other) {
            // Don't worry about copying collections here. This is handled by build().
            startDateTime = other.startDateTime;
            endDateTime = other.endDateTime;
            exporterDdbPrefixOverride = other.exporterDdbPrefixOverride;
            recordIdS3Override = other.recordIdS3Override;
            redriveCount = other.redriveCount;
            sharingMode = other.sharingMode;
            studyWhitelist = other.studyWhitelist;
            synapseProjectOverrideMap = other.synapseProjectOverrideMap;
            tableWhitelist = other.tableWhitelist;
            tag = other.tag;
            useLastExportTime = other.useLastExportTime;
            return this;
        }

        /** @see BridgeExporterRequest#getStartDateTime()  */
        @JsonDeserialize(using = DateTimeDeserializer.class)
        public Builder withStartDateTime(DateTime startDateTime) {
            this.startDateTime = startDateTime;
            return this;
        }

        /** @see BridgeExporterRequest#getEndDateTime */
        @JsonDeserialize(using = DateTimeDeserializer.class)
        public Builder withEndDateTime(DateTime endDateTime) {
            this.endDateTime = endDateTime;
            return this;
        }

        /** @see BridgeExporterRequest#getExporterDdbPrefixOverride */
        public Builder withExporterDdbPrefixOverride(String exporterDdbPrefixOverride) {
            this.exporterDdbPrefixOverride = exporterDdbPrefixOverride;
            return this;
        }

        /** @see BridgeExporterRequest#getRecordIdS3Override */
        public Builder withRecordIdS3Override(String recordIdS3Override) {
            this.recordIdS3Override = recordIdS3Override;
            return this;
        }

        /** @see BridgeExporterRequest#getRedriveCount */
        public Builder withRedriveCount(int redriveCount) {
            this.redriveCount = redriveCount;
            return this;
        }

        /** @see BridgeExporterRequest#getSharingMode */
        public Builder withSharingMode(BridgeExporterSharingMode sharingMode) {
            this.sharingMode = sharingMode;
            return this;
        }

        /** @see BridgeExporterRequest#getStudyWhitelist */
        @JsonAlias("appWhitelist")
        public Builder withStudyWhitelist(Set<String> studyWhitelist) {
            this.studyWhitelist = studyWhitelist;
            return this;
        }
        
        /** @see BridgeExporterRequest#getSynapseProjectOverrideMap */
        public Builder withSynapseProjectOverrideMap(Map<String, String> synapseProjectOverrideMap) {
            this.synapseProjectOverrideMap = synapseProjectOverrideMap;
            return this;
        }

        /** @see BridgeExporterRequest#getTableWhitelist */
        public Builder withTableWhitelist(Set<UploadSchemaKey> tableWhitelist) {
            this.tableWhitelist = tableWhitelist;
            return this;
        }

        /** @see BridgeExporterRequest#getTag */
        public Builder withTag(String tag) {
            this.tag = tag;
            return this;
        }

        /** @see BridgeExporterRequest#getUseLastExportTime */
        public Builder withUseLastExportTime(boolean useLastExportTime) {
            this.useLastExportTime = useLastExportTime;
            return this;
        }

        /** Builds a Bridge EX request object and validates all parameters. */
        public BridgeExporterRequest build() {
            // useLastExportTime must be specified
            if (useLastExportTime == null) {
                throw new IllegalStateException("useLastExportTime must be specified.");
            }

            // Exactly one of useLastExportTime=true, startDateTime, and recordIdS3Override must be specified.
            boolean hasStartDateTime = startDateTime != null;
            boolean hasEndDateTime = endDateTime != null;
            boolean hasRecordIdS3Override = StringUtils.isNotBlank(recordIdS3Override);
            int numRecordSources = 0;
            if (useLastExportTime) {
                numRecordSources++;
            }
            if (hasStartDateTime) {
                numRecordSources++;
            }
            if (hasRecordIdS3Override) {
                numRecordSources++;
            }
            if (numRecordSources != 1) {
                throw new IllegalStateException("Exactly one of useLastExportTime=true, startDateTime, and " +
                        "recordIdS3Override must be specified.");
            }

            // If useLastExportTime=true, then endDateTime must be specified.
            if (useLastExportTime && !hasEndDateTime) {
                throw new IllegalStateException("If useLastExportTime=true, the endDateTime must be specified.");
            }

            // If startDateTime is specified, so must endDateTime.
            if (hasStartDateTime) {
                if (!hasEndDateTime) {
                    throw new IllegalStateException("Should specify end date time if specified start date time.");
                }
                if (!startDateTime.isBefore(endDateTime)) {
                    throw new IllegalStateException("StartDateTime must be before endDateTime.");
                }
            }

            // If recordIdS3Override is specified, you can't have an endDateTime.
            if (hasEndDateTime && hasRecordIdS3Override) {
                throw new IllegalStateException("Cannot specify both recordIdS3Override and end date time.");
            }

            // If exporterDdbPrefixOverride is specified, then so must synapseProjectOverrideMap, and vice versa.
            boolean hasExporterDdbPrefixOverride = StringUtils.isNotBlank(exporterDdbPrefixOverride);
            boolean hasSynapseProjectOverrideMap = synapseProjectOverrideMap != null;
            if (hasExporterDdbPrefixOverride ^ hasSynapseProjectOverrideMap) {
                throw new IllegalStateException("exporterDdbPrefixOverride and synapseProjectOverrideMap must both " +
                        "be specified or both be absent.");
            }

            // synapseProjectOverrideMap can be unspecified (null), but it's semantically unclear if it's an empty map.
            // Therefore, if synapseProjectOverrideMap is specified, it can't be empty.
            if (hasSynapseProjectOverrideMap && synapseProjectOverrideMap.isEmpty()) {
                throw new IllegalStateException("If synapseProjectOverrideMap is specified, it can't be empty.");
            }

            // Similarly, studyWhitelist and tableWhitelist can be unspecified, but can't be empty.
            if (studyWhitelist != null && studyWhitelist.isEmpty()) {
                throw new IllegalStateException("If studyWhitelist is specified, it can't be empty.");
            }

            if (tableWhitelist != null && tableWhitelist.isEmpty()) {
                throw new IllegalStateException("If tableWhitelist is specified, it can't be empty.");
            }

            // sharingMode defaults to SHARED if not specified
            if (sharingMode == null) {
                sharingMode = BridgeExporterSharingMode.SHARED;
            }

            // Tag is optional but must be non-null. If null, replace with default tag.
            if (tag == null) {
                tag = DEFAULT_TAG;
            }

            // Replace collections with immutable copies, to maintain request object integrity.
            if (studyWhitelist != null) {
                studyWhitelist = ImmutableSet.copyOf(studyWhitelist);
            }

            if (hasSynapseProjectOverrideMap) {
                synapseProjectOverrideMap = ImmutableMap.copyOf(synapseProjectOverrideMap);
            }

            if (tableWhitelist != null) {
                tableWhitelist = ImmutableSet.copyOf(tableWhitelist);
            }

            return new BridgeExporterRequest(startDateTime, endDateTime, exporterDdbPrefixOverride, recordIdS3Override,
                    redriveCount, sharingMode, studyWhitelist, synapseProjectOverrideMap,
                    tableWhitelist, tag, useLastExportTime);
        }
    }
}
