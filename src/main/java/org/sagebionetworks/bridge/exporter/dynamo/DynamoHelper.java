package org.sagebionetworks.bridge.exporter.dynamo;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Resource;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.jcabi.aspects.Cacheable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.json.DefaultObjectMapper;

/**
 * Encapsulates common calls Bridge-EX makes to DDB. Some of these have a little bit of logic or require marshalling
 * from a DDB item to a Bridge-EX object. Others are there just for convenience, so we have all or most of our DDB
 * interactions through a single class.
 */
@Component
public class DynamoHelper {
    private static final Logger LOG = LoggerFactory.getLogger(DynamoHelper.class);

    private static final String STUDY_INFO_KEY_DATA_ACCESS_TEAM = "synapseDataAccessTeamId";
    private static final String STUDY_INFO_KEY_PROJECT_ID = "synapseProjectId";
    private static final String STUDY_INFO_KEY_USES_CUSTOM_EXPORT_SCHEDULE = "usesCustomExportSchedule";
    static final String STUDY_DISABLE_EXPORT = "disableExport";

    private Table ddbParticipantOptionsTable;
    private Table ddbStudyTable;

    /** Participant options table, used to get user sharing scope. */
    @Resource(name = "ddbParticipantOptionsTable")
    public final void setDdbParticipantOptionsTable(Table ddbParticipantOptionsTable) {
        this.ddbParticipantOptionsTable = ddbParticipantOptionsTable;
    }

    /** Study table, used to get study config, like linked Synapse project. */
    @Resource(name = "ddbStudyTable")
    public final void setDdbStudyTable(Table ddbStudyTable) {
        this.ddbStudyTable = ddbStudyTable;
    }

    /**
     * Gets the sharing scope for the given user.
     *
     * @param healthCode
     *         health code of user to get sharing scope for
     * @return the user's sharing scope
     */
    @Cacheable(lifetime = 5, unit = TimeUnit.MINUTES)
    public SharingScope getSharingScopeForUser(String healthCode) {
        // default sharing scope is no sharing
        SharingScope sharingScope = SharingScope.NO_SHARING;

        try {
            Item participantOptionsItem = ddbParticipantOptionsTable.getItem("healthDataCode", healthCode);
            if (participantOptionsItem != null) {
                String participantOptionsData = participantOptionsItem.getString("data");
                Map<String, Object> participantOptionsDataMap = DefaultObjectMapper.INSTANCE.readValue(
                        participantOptionsData, DefaultObjectMapper.TYPE_REF_RAW_MAP);
                String sharingScopeStr = String.valueOf(participantOptionsDataMap.get("SHARING_SCOPE"));

                // put this in its own try-catch block for better logging
                try {
                    sharingScope = SharingScope.valueOf(sharingScopeStr);
                } catch (IllegalArgumentException ex) {
                    LOG.error("Unable to parse sharing options for hash[healthCode]=" + healthCode.hashCode() +
                            ", sharing scope value=" + sharingScopeStr);
                }
            }
        } catch (IOException | RuntimeException ex) {
            // log an error, fall back to default
            LOG.error("Unable to get sharing options for hash[healthCode]=" + healthCode.hashCode() + ": " +
                    ex.getMessage(), ex);
        }

        return sharingScope;
    }

    /**
     * Get study info, namely Synapse project and data access team.
     *
     * @param studyId
     *         study ID to fetch
     * @return study info
     */
    @Cacheable(lifetime = 5, unit = TimeUnit.MINUTES)
    public StudyInfo getStudyInfo(String studyId) {
        Item studyItem = ddbStudyTable.getItem("identifier", studyId);

        // DDB's Item.getLong() will throw if the value is null. For robustness, check get() the value and check that
        // it's not null. (This should cover both cases where the attribute doesn't exist and cases where the attribute
        // exists and is null.
        StudyInfo.Builder studyInfoBuilder = new StudyInfo.Builder();

        // we need to check at first if the study is disabled exporting or not
        // treat null as false
        boolean disableExport = false;

        if (studyItem.get(STUDY_DISABLE_EXPORT) != null) {
            if (studyItem.getInt(STUDY_DISABLE_EXPORT) != 0) {
                disableExport = true;
            }
        }

        if (!disableExport) {
            if (studyItem.get(STUDY_INFO_KEY_DATA_ACCESS_TEAM) != null) {
                studyInfoBuilder.withDataAccessTeamId(studyItem.getLong(STUDY_INFO_KEY_DATA_ACCESS_TEAM));
            }

            if (studyItem.get(STUDY_INFO_KEY_PROJECT_ID) != null) {
                studyInfoBuilder.withSynapseProjectId(studyItem.getString(STUDY_INFO_KEY_PROJECT_ID));
            }

            if (studyItem.get(STUDY_INFO_KEY_USES_CUSTOM_EXPORT_SCHEDULE) != null) {
                // For some reason, the mapper saves the value as an int, not as a boolean.
                boolean usesCustomExportSchedule = studyItem.getInt(STUDY_INFO_KEY_USES_CUSTOM_EXPORT_SCHEDULE) != 0;
                studyInfoBuilder.withUsesCustomExportSchedule(usesCustomExportSchedule);
            }
        }

        return studyInfoBuilder.build();
    }
}
