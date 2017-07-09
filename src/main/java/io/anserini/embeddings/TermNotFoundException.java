package io.anserini.embeddings;

/**
 * Created by rdsequie on 08/07/17.
 */
public class TermNotFoundException extends Exception {
    public TermNotFoundException(String term) {
        super(term);
    }
}
