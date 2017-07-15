package io.anserini.document;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * A Twitter document (status).
 */
public class TwitterDocument implements SourceDocument {
  private static final Logger LOG = LogManager.getLogger(io.anserini.document.twitter.Status.class);

  private String id;
  private String contents;

  private static final JsonParser JSON_PARSER = new JsonParser();


  public SourceDocument readNextRecord(String json) throws IOException {
    JsonObject obj = null;
    try {
      obj = (JsonObject) JSON_PARSER.parse(json);
    } catch (Exception e) {
      // Catch any malformed JSON.
      LOG.error("Error parsing: " + json);
      return null;
    }

    if (obj.get("text") == null) {
      contents = null;
      return null;
    }

    contents = obj.get("text").getAsString();
    id = obj.get("id").getAsString();
    System.out.println(id);
    return this;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String content() {
    return contents;
  }

  @Override
  public boolean indexable() {
    return true;
  }
}
