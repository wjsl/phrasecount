package phrasecount;

import static phrasecount.Constants.DOC_CONTENT_COL;
import static phrasecount.Constants.DOC_REF_COUNT_COL;
import static phrasecount.Constants.EXPORT_CHECK_COL;
import static phrasecount.Constants.EXPORT_DOC_COUNT_COL;
import static phrasecount.Constants.EXPORT_SUM_COL;
import static phrasecount.Constants.INDEX_STATUS_COL;
import static phrasecount.Constants.STAT_DOC_COUNT_COL;
import static phrasecount.Constants.STAT_SUM_COL;
import static phrasecount.Constants.TYPEL;

import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.accumulo.core.data.ByteSequence;

import accismus.api.Column;
import accismus.api.Observer;
import accismus.api.Transaction;
import accismus.api.types.TypedSnapshot.Value;
import accismus.api.types.TypedTransaction;

import com.google.common.collect.Sets;

public class PhraseCounter implements Observer {

  private static enum IndexStatus {
    INDEXED, UNINDEXED
  }

  public void process(Transaction tx, ByteSequence row, Column col) throws Exception {

    TypedTransaction ttx = TYPEL.transaction(tx);

    IndexStatus status = getStatus(ttx, row);
    int refCount = ttx.get().row(row).col(DOC_REF_COUNT_COL).toInteger();

    if (status == IndexStatus.UNINDEXED && refCount > 0) {
      updatePhraseCounts(ttx, row, 1);
      ttx.set().row(row).col(INDEX_STATUS_COL).val(IndexStatus.INDEXED.name());
    } else if (status == IndexStatus.INDEXED && refCount == 0) {
      updatePhraseCounts(ttx, row, -1);
      deleteDocument(ttx, row);
    }

    tx.delete(row, col);

  }

  private void deleteDocument(TypedTransaction tx, ByteSequence row) {
    // TODO it would probably be useful to have a deleteRow method on Transaction... this method could start off w/ a simple implementation and later be
    // optimized... or could have a delete range option

    // TODO this is brittle, this code assumes it knows all possible columns
    tx.delete(row, DOC_CONTENT_COL);
    tx.delete(row, DOC_REF_COUNT_COL);
    tx.delete(row, INDEX_STATUS_COL);

  }

  private void updatePhraseCounts(TypedTransaction ttx, ByteSequence row, int multiplier) throws Exception {
    String content = ttx.get().row(row).col(Constants.DOC_CONTENT_COL).toString();

    // this makes the assumption that the implementation of getPhrases is invariant. This is probably a bad assumption. A possible way to make this more robust
    // is to store the output of getPhrases when indexing and use the stored output when unindexing. Alternatively, could store the version of Document used for
    // indexing.
    Map<String,Integer> phrases = new Document(null, content).getPhrases();

    ArrayList<String> rows = new ArrayList<String>(phrases.size());

    for (String phrase : phrases.keySet()) {
      String phraseRow = "phrase:" + phrase;
      rows.add(phraseRow);
    }

    Map<String,Map<Column,Value>> storedPhrases = ttx.getd(rows, Sets.newHashSet(STAT_SUM_COL, STAT_DOC_COUNT_COL, EXPORT_SUM_COL, EXPORT_DOC_COUNT_COL));

    for (Entry<String,Integer> entry : phrases.entrySet()) {
      String phrase = entry.getKey();
      String phraseRow = "phrase:" + phrase;

      Map<Column,Value> columns = storedPhrases.get(phraseRow);
      int sum = columns.get(STAT_SUM_COL).toInteger(0);
      int docCount = columns.get(STAT_DOC_COUNT_COL).toInteger(0);

      int newSum = sum + multiplier * entry.getValue();
      int newDocCount = docCount + multiplier * 1;

      if (newSum > 0) {
        // trigger the export observer to process changes data
        ttx.set().row(phraseRow).col(EXPORT_CHECK_COL).val();
        ttx.set().row(phraseRow).col(STAT_SUM_COL).val(newSum);
      } else
        ttx.delete().row(phraseRow).col(STAT_SUM_COL);

      if (newDocCount > 0)
        ttx.set().row(phraseRow).col(STAT_DOC_COUNT_COL).val(newDocCount);
      else
        ttx.delete().row(phraseRow).col(STAT_DOC_COUNT_COL);

      if (!columns.containsKey(EXPORT_SUM_COL)) {
        // only update export columns if not set... once set, these columns can only be changed by the export observer
        ttx.set().row(phraseRow).col(EXPORT_SUM_COL).val(newSum);
        ttx.set().row(phraseRow).col(EXPORT_DOC_COUNT_COL).val(newDocCount);
      }
    }
  }

  private IndexStatus getStatus(TypedTransaction tx, ByteSequence row) throws Exception {
    String status = tx.get().row(row).col(INDEX_STATUS_COL).toString();

    if (status == null)
      return IndexStatus.UNINDEXED;

    return IndexStatus.valueOf(status);
  }

}