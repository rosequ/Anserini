# Anserini Experiments on TREC Microblog Collections

Indexing:

```
nohup sh target/appassembler/bin/IndexCollection -collection TwitterCollection \
 -input /path/to/tweet/collection/ -generator LuceneDocumentGenerator \
 -index lucene-index.twitter.pos+docvectors -threads 16 -storePositions -storeDocvectors -optimize \
 > log.core.pos+docvectors &

```

The directory `/path/to/tweet/collection/` should be the root directory of statuses present in `.gz` format. 
The command above builds a standard positional index (`-storePositions`) that's optimized into a single segment 
(`-optimize`). If you also want to store document vectors (e.g., for query expansion), add the `-docvectors` option.  
The above command builds an index that stores term positions (`-storePositions`) as well as doc vectors for relevance 
feedback (`-storeDocvectors`), and `-optimize` force merges all index segment into one.

After indexing is done, you should be able to perform a retrieval as follows:

```
sh target/appassembler/bin/SearchTweets \
  -topicreader Trec -index lucene-index.core.pos+docvectors -bm25 \
  -topics src/main/resources/topics-and-qrels/topics.microblog2011.txt -output run.microblog2011.bm25.txt
```

The above command shows searching for TREC Microblog 2011 topics. Similarly, you should be able to search with other 
TREC Microblog topics. 
For the retrieval model: specify `-bm25` to use BM25, `-ql` to use query likelihood, and add `-rm3` to invoke the RM3 
relevance feedback model (requires docvectors index). 

