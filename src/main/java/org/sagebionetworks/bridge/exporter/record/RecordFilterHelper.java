package org.sagebionetworks.bridge.exporter.record;

import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.google.common.cache.LoadingCache;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.exporter.metrics.Metrics;
import org.sagebionetworks.bridge.exporter.request.BridgeExporterRequest;
import org.sagebionetworks.bridge.exporter.sharing.BridgeExporterSharingMode;
import org.sagebionetworks.bridge.exporter.schema.UploadSchemaHelper;
import org.sagebionetworks.bridge.exporter.sharing.SharingScope;
import org.sagebionetworks.bridge.schema.UploadSchemaKey;

// TODO doc
public class RecordFilterHelper {
    private static final Logger LOG = LoggerFactory.getLogger(RecordFilterHelper.class);

    private LoadingCache<String, SharingScope> userSharingScopeCache;

    public final void setUserSharingScopeCache(LoadingCache<String, SharingScope> userSharingScopeCache) {
        this.userSharingScopeCache = userSharingScopeCache;
    }

    public boolean shouldFilterRecord(Metrics metrics, BridgeExporterRequest request, Item record) {
        // request always has a sharing mode
        boolean filterBySharingScope = shouldFilterRecordBySharingScope(metrics, request.getSharingMode(), record);

        // filter by study - This is used for filtering out test studies and for limiting study-specific exports.
        boolean filterByStudy = false;
        Set<String> studyFilterSet = request.getStudyFilterSet();
        if (studyFilterSet != null && !studyFilterSet.isEmpty()) {
            filterByStudy = shouldFilterRecordByStudy(metrics, studyFilterSet, record.getString("studyId"));
        }

        // filter by table - This is used for table-specific redrives.
        boolean filterByTable = false;
        Set<UploadSchemaKey> tableFilterSet = request.getTableFilterSet();
        if (tableFilterSet != null && !tableFilterSet.isEmpty()) {
            filterByTable = shouldFilterRecordByTable(metrics, tableFilterSet,
                    UploadSchemaHelper.getSchemaKeyForRecord(record));
        }

        // If any of the filters are hit, we filter the record. (We don't use short-circuiting because we want to
        // collect the metrics.)
        return filterBySharingScope || filterByStudy || filterByTable;
    }

    private boolean shouldFilterRecordBySharingScope(Metrics metrics, BridgeExporterSharingMode sharingMode,
            Item record) {
        // Get the record's sharing scope. Defaults to no_sharing if it's not present or unable to be parsed.
        SharingScope recordSharingScope;
        String recordSharingScopeStr = record.getString("userSharingScope");
        try {
            recordSharingScope = SharingScope.valueOf(recordSharingScopeStr);
        } catch (IllegalArgumentException ex) {
            LOG.error("Could not parse sharing scope " + recordSharingScopeStr);
            recordSharingScope = SharingScope.NO_SHARING;
        }

        // Get sharing scope from user's participant options.
        SharingScope userSharingScope;
        String healthCode = record.getString("healthCode");
        try {
            userSharingScope = userSharingScopeCache.get(healthCode);
        } catch (ExecutionException ex) {
            LOG.error("Could not get sharing scope for hash[healthCode]=" + healthCode.hashCode() + ": " +
                    ex.getMessage(), ex);
            userSharingScope = SharingScope.NO_SHARING;
        }

        // reconcile both sharing scopes to find the most restrictive sharing scope
        SharingScope sharingScope;
        if (SharingScope.NO_SHARING.equals(recordSharingScope) || SharingScope.NO_SHARING.equals(userSharingScope)) {
            sharingScope = SharingScope.NO_SHARING;
        } else if (SharingScope.SPONSORS_AND_PARTNERS.equals(recordSharingScope) ||
                SharingScope.SPONSORS_AND_PARTNERS.equals(userSharingScope)) {
            sharingScope = SharingScope.SPONSORS_AND_PARTNERS;
        } else if (SharingScope.ALL_QUALIFIED_RESEARCHERS.equals(recordSharingScope) ||
                SharingScope.ALL_QUALIFIED_RESEARCHERS.equals(userSharingScope)) {
            sharingScope = SharingScope.ALL_QUALIFIED_RESEARCHERS;
        } else {
            throw new IllegalArgumentException("Impossible code path in RecordFilterHelper.shouldFilterRecordBySharingScope(): recordSharingScope=" +
                    recordSharingScope + ", userSharingScope=" + userSharingScope);
        }

        // actual filter logic here
        if (sharingMode.shouldFilterScope(sharingScope)) {
            metrics.incrementCounter("filtered[" + sharingScope.name() + "]");
            return true;
        } else {
            metrics.incrementCounter("accepted[" + sharingScope.name() + "]");
            return false;
        }
    }

    private boolean shouldFilterRecordByStudy(Metrics metrics, Set<String> studyFilterSet, String studyId) {
        if (StringUtils.isBlank(studyId)) {
            throw new IllegalArgumentException("record has no study ID");
        }

        // studyFilterSet is the set of studies that we accept
        if (studyFilterSet.contains(studyId)) {
            metrics.incrementCounter("accepted[" + studyId + "]");
            return false;
        } else {
            metrics.incrementCounter("filtered[" + studyId + "]");
            return true;
        }
    }

    private boolean shouldFilterRecordByTable(Metrics metrics, Set<UploadSchemaKey> tableFilterSet,
            UploadSchemaKey schemaKey) {
        // tableFilterSet is the set of tables that we accept
        if (tableFilterSet.contains(schemaKey)) {
            metrics.incrementCounter("accepted[" + schemaKey + "]");
            return false;
        } else {
            metrics.incrementCounter("filtered[" + schemaKey + "]");
            return true;
        }
    }
}
