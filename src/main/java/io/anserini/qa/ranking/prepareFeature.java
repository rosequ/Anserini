package io.anserini.qa.ranking;

import io.anserini.index.generator.LuceneDocumentGenerator;
import org.apache.commons.lang.StringUtils;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.store.FSDirectory;

import java.io.*;
import java.util.*;

/**
 * Created by royalsequeira on 2017-06-02.
 */

public class prepareFeature {
  private List<String> stopWords = null;
  private final FSDirectory directory;
  private final DirectoryReader reader;

  public prepareFeature(String index) throws IOException {
    stopWords = new ArrayList<>();
    this.directory = FSDirectory.open(new File(index).toPath());
    this.reader = DirectoryReader.open(directory);

    InputStream is = getClass().getResourceAsStream("/io/anserini/qa/english-stoplist.txt");
    BufferedReader bRdr = new BufferedReader(new InputStreamReader(is));
    String line;
    while ((line = bRdr.readLine()) != null) {
      if (!line.contains("#")) {
        stopWords.add(line);
      }
    }
  }

  public Set<String> removeStopWords(String candidate) throws Exception {
    StandardAnalyzer sa = new StandardAnalyzer();
    TokenStream tokenStream = sa.tokenStream("contents", new StringReader(candidate));
    CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);
    tokenStream.reset();

    Set<String> stopWordRemoved = new HashSet<>();
    while (tokenStream.incrementToken()) {
      stopWordRemoved.add(charTermAttribute.toString());
    }
    return stopWordRemoved;
  }

  public double computeOverlap(String question, String answer, boolean stop) throws Exception {
    Set<String> questionSet = new HashSet<> (Arrays.asList(question.split("\\t")));
    Set<String> answerSet = new HashSet<> (Arrays.asList(answer.split("\\t")));
    Set<String> commonSet = new HashSet <>(questionSet);
    commonSet.retainAll(answerSet);

    if (stop) {
      commonSet = removeStopWords(StringUtils.join(commonSet, " "));
    }

    double overlap = (commonSet.size() * 1.0) / (questionSet.size() + answerSet.size());
    return overlap;
  }

  public double idfWeightedOverlap(String question, String answer, boolean stop)
          throws ParseException, IOException {
    ClassicSimilarity similarity = new ClassicSimilarity();
    EnglishAnalyzer ea = stop ? new EnglishAnalyzer(CharArraySet.EMPTY_SET) : new EnglishAnalyzer(StopFilter.makeStopSet(stopWords));
    QueryParser qp = new QueryParser(LuceneDocumentGenerator.FIELD_BODY, ea);

    Set<String> questionSet = new HashSet<> (Arrays.asList(question.split("\\t")));
    Set<String> answerSet = new HashSet<> (Arrays.asList(answer.split("\\t")));
    Set<String> commonSet = new HashSet <>(questionSet);
    commonSet.retainAll(answerSet);

    double overlap = 0.0;
    for (String term : commonSet) {
      TermQuery q = (TermQuery) qp.parse(term);
      Term t = q.getTerm();

      overlap +=  similarity.idf(reader.docFreq(t), reader.numDocs());
    }
    overlap /= (questionSet.size() + answerSet.size());
    return overlap;
  }

}
