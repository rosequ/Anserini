import argparse
import os
import re

from nltk.tokenize import TreebankWordTokenizer
from pyserini import Pyserini
from sm_cnn.bridge import SMModelBridge
import hashlib

class QAModel:
    instance = None
    def __init__(self, model_choice, index_path, w2v_cache, qa_model_file):
        if model_choice == "sm":
            QAModel.instance = SMModelBridge(qa_model_file, w2v_cache, index_path)


def get_answers(pyserini, question, h0, h1, model_choice, index_path, w2v_cache="", qa_model_file=""):
    candidate_passages_scores = pyserini.ranked_passages(question, h0, h1)
    idf_json = pyserini.get_term_idf_json()
    candidate_passages = []
    tokeninzed_answer = []

    if model_choice == 'sm':

        qa_DL_model = QAModel(model_choice, index_path, w2v_cache, qa_model_file).instance

        # for ps in candidate_passages_scores:
        #     ps_split = ps.split('\t')
        #     candidate_passages.append(ps_split)

        # TODO: for processing input data. Ideally these settings need to come in as program arguments
        # NOTE: the best model for TrecQA keeps punctuation and keeps dash-words
        flags = {
          "punctuation": "", # ignoring for now  you can {keep|remove} punctuation
          "dash_words": "" # ignoring for now. you can {keep|split} words-with-hyphens
        }
        answers_list = qa_DL_model.rerank_candidate_answers(question, candidate_passages_scores, idf_json, flags)
        sorted_answers = sorted(answers_list, key=lambda x: x[0], reverse=True)

        for candidate, docid, score in sorted_answers:
            tokens = TreebankWordTokenizer().tokenize(candidate.lower().split("\t")[0])
            tokeninzed_answer.append((tokens, docid, score))
        return tokeninzed_answer

    elif model_choice == 'idf':
        for candidate in candidate_passages_scores:
            candidate_sent, docid, score = candidate.lower().split("\t")
            tokens = TreebankWordTokenizer().tokenize(candidate_sent.lower())
            tokeninzed_answer.append((tokens, docid, score))
        return tokeninzed_answer

def load_data(fname):
    questions = []
    answers = {}
    labels = {}
    prev = ''
    answer_count = 0

    with open(fname, 'r') as f:
        for line in f:
            line = line.strip()
            qid_match = re.match('<QApairs id=\'(.*)\'>', line)

            if qid_match:
                answer_count = 0
                qid = qid_match.group(1)

                if qid not in answers:
                    answers[qid] = []

                if qid not in labels:
                    labels[qid] = {}

            if prev and prev.startswith('<question>'):
                questions.append(line)

            label = re.match('^<(positive|negative)>', prev)

            if label:
                label = label.group(1)
                label = 1 if label == 'positive' else 0
                answer = line.lower().split('\t')
                answer_count += 1

                answer_id = 'Q{}-A{}'.format(qid, answer_count)
                answers[qid].append((answer_id, answer, label))
                labels[qid][answer_id] = label
            prev = line

    return questions, answers, labels

def separate_docs(pattern):
    doc_pats = pattern.split()
    doc, pats = [], []

    for term in pattern.split():
        if "XIE" in term or "APW" in term or "NYT" in term:
            doc.append(term.lower())
        else:
            pats.append(term)

    return " ".join(pats), doc

correct = 0.0
total_correct = 0.0

# evaluate by TREC QA patterns
def eval_by_pattern(qid, candidate, pattern):
    global correct
    global total_correct
    pattern, docs = separate_docs(pattern)

    this_candidate = " ".join(candidate[0])
    docid, score = candidate[1], candidate[2]

    print(qid, docid, this_candidate)
    result = re.findall(r'{}'.format(pattern.lower()), this_candidate)
    if result and docid in docs:
        print(qid, docid)
        correct += 1
        total_correct += 1
    else:
        # print(qid, docid, this_candidate)
        total_correct += 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Evaluate the QA system')
    parser.add_argument('-input', help='path of a TrecQA file', required=True)
    parser.add_argument("-output", help="path of output file", default=".")
    parser.add_argument("-qrel", help="path of qrel file")
    parser.add_argument("-h0", help="number of hits", default=1000)
    parser.add_argument("-h1", help="h1 passages to be reranked", default=100)
    parser.add_argument("-model", help="[idf|sm]", default='idf')
    parser.add_argument('-index', help="path of the index", required=True)
    parser.add_argument('-w2v-cache', help="word embeddings cache file")
    parser.add_argument('-qa-model-file', help="the path to the model file")
    parser.add_argument('-pattern', help='path to the pattern file')

    args = parser.parse_args()

    if (args.model == "sm" and not args.w2v_cache and not args.qa_model_file):
        print("Pass the word embeddings cache file and the model file")
        parser.print_help()
        exit()

    pattern_dict = {}

    if not args.pattern:
        print("Path to the pattern file should be included if the method of evaluation is pattern based")
        parser.print_help()
        exit()
    else:
        with open(args.pattern, 'r') as pattern_file:
            for line in pattern_file:
                det = line.strip().split(' ', 1)

                if det[0] not in pattern_dict:
                    pattern_dict[det[0]] = det[1]

    pyserini = Pyserini(args.index)
    questions, answers, labels_actual = load_data(args.input)

    for qid, question in zip(answers.keys(), questions):
        # sentence re-ranking after ad-hoc retrieval
        candidates = get_answers(pyserini, question, int(args.h0), int(args.h1), args.model, args.index, args.w2v_cache,
                               args.qa_model_file)

        try:
            eval_by_pattern(qid, candidates[0], pattern_dict[qid])
        except KeyError as e:
            print("Pattern not found for question: {}".format(e))

    print("Accuracy:{}".format(str(correct/total_correct)))

