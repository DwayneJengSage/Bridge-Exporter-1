package org.sagebionetworks.bridge.synapse;

import java.util.List;
import java.util.NoSuchElementException;

import com.google.common.base.Strings;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.exceptions.SynapseClientException;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.repo.model.table.QueryNextPageToken;
import org.sagebionetworks.repo.model.table.QueryResult;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.Row;

/** Helper class to query Synapse tables and iterate over the results, abstracting away pagination. */
// This doesn't implement Iterator, since Iterator's methods can't throw checked exceptions.
public class SynapseTableIterator {
    private static final int ASYNC_QUERY_TIMEOUT_SECONDS = 30;

    // Constructor args.
    private final SynapseClient synapseClient;
    private final String synapseTableId;

    // Internal state tracking.
    private String asyncJobToken;
    private QueryResult curResult;
    private int curRowNumInPage = 0;
    private String etag;
    private boolean firstPage = true;
    private Row nextRow;

    /**
     * Creates the Synapse table iterator with the specified args.
     *
     * @param synapseClient
     *         synapse client
     * @param sql
     *         SQL query to run, defaults to "SELECT * FROM [synapseTableId]"
     * @param synapseTableId
     *         synapse table ID to run the query against
     * @throws SynapseException
     *         if the synapse call fails
     */
    public SynapseTableIterator(SynapseClient synapseClient, String sql, String synapseTableId)
            throws SynapseException {
        if (Strings.isNullOrEmpty(sql)) {
            sql = "SELECT * FROM " + synapseTableId;
        }

        this.synapseClient = synapseClient;
        this.synapseTableId = synapseTableId;
        this.asyncJobToken = synapseClient.queryTableEntityBundleAsyncStart(sql, null, null, true,
                SynapseClient.QUERY_PARTMASK, synapseTableId);
    }

    /**
     * Returns true if the iterator has additional elements. Does not advance the iterator.
     *
     * @return true if the iterator has additional elements.
     * @throws SynapseException
     *         if the underlying Synapse call fails
     */
    // Checks if a row is available, and if so, loads it into nextRow.
    public boolean hasNext() throws SynapseException {
        if (nextRow != null) {
            // Next row is already loaded and ready.
            return true;
        }

        if (asyncJobToken != null) {
            // We have a page to fetch.
            fetchNextPage();

            // Once we've fetched the page, wipe the asyncJobToken so we don't try to fetch it again.
            asyncJobToken = null;

            // If we just fetched a page with no rows, then this means we've hit the end of the stream. Return false.
            if (curResult.getQueryResults().getRows().isEmpty()) {
                curResult = null;
                return false;
            }
        }

        if (curResult != null) {
            List<Row> rowList = curResult.getQueryResults().getRows();
            // This should be fine, since we always wipe the curResults when we get to the end of the page.
            nextRow = rowList.get(curRowNumInPage);

            // Advance the row num.
            curRowNumInPage++;
            if (curRowNumInPage == rowList.size()) {
                // If that was the last row and we have a next page token, go ahead and set up the
                // next page.
                QueryNextPageToken nextPageToken = curResult.getNextPageToken();
                if (nextPageToken != null) {
                    asyncJobToken = synapseClient.queryTableEntityNextPageAsyncStart(nextPageToken.getToken(),
                            synapseTableId);
                }

                // Wipe out the curResult, since we're done with it. This allows the iterator to remember that we
                // need to fetch the next page. Also, the row num since we're in a new page.
                curResult = null;
                curRowNumInPage = 0;
            }

            return true;
        }

        // This is the end.
        return false;
    }

    /**
     * Returns the next element in the iterator. Throws a NoSuchElementException if no such element exists.
     *
     * @return the next element in the iterator
     * @throws NoSuchElementException
     *         if no such element exists
     * @throws SynapseException
     *         if an underlying Synapse call fails
     */
    // Call hasNext() first to check if we have a row or load the next row if needed.
    public Row next() throws NoSuchElementException, SynapseException {
        if (hasNext()) {
            // clear the nextRow, so hasNext() knows to load the next one
            Row retVal = nextRow;
            nextRow = null;
            return retVal;
        } else {
            throw new NoSuchElementException();
        }
    }

    /**
     * Returns the etag from the first page of results. This doesn't get updated with subsequent page fetches, so if
     * there's an intravening update, the etag will reflect that the query results are outdated.
     *
     * @return the etag from the query
     * @throws SynapseException
     *         if an underlying Synapse call fails
     */
    public String getEtag() throws SynapseException {
        if (etag == null) {
            // Force initialization by calling hasNext(), which fetches the page. This is safe, because hasNext() will
            // only fetch the page if it hasn't already been fetched.
            hasNext();
        }
        return etag;
    }

    private void fetchNextPage() throws SynapseException {
        // poll asyncGet until success or timeout
        boolean success = false;
        for (int sec = 0; sec < ASYNC_QUERY_TIMEOUT_SECONDS; sec++) {
            // sleep for 1 sec
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                // noop
            }

            // poll
            try {
                if (firstPage) {
                    // This is the first page, so we call bundle get instead of next page get.
                    QueryResultBundle resultBundle = synapseClient.queryTableEntityBundleAsyncGet(asyncJobToken,
                            synapseTableId);
                    curResult = resultBundle.getQueryResult();

                    // fetch etag
                    etag = curResult.getQueryResults().getEtag();

                    // This is no longer the first page.
                    firstPage = false;
                } else {
                    // We're getting a next page.
                    curResult = synapseClient.queryTableEntityNextPageAsyncGet(asyncJobToken, synapseTableId);
                }

                // If this doesn't throw, we've successfully fetched the page.
                success = true;
                break;
            } catch (SynapseResultNotReadyException ex) {
                // results not ready, sleep some more
            }
        }

        if (!success) {
            throw new SynapseClientException("Timed out querying table " + synapseTableId);
        }
    }
}