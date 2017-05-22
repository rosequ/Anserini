import argparse
import os
import re
import random

from nltk.tokenize import TreebankWordTokenizer
from pyserini import Pyserini

from sm_cnn.bridge import SMModelBridge

pref_dict = {0:'idf', 1:'sm'}
class QAModel:
  instance = None
  def __init__(self, model_choice, index_path, w2v_cache, qa_model_file):
    if not QAModel.instance:
       path = os.getcwd() + '/..'
       if model_choice == "sm":         
         QAModel.instance = SMModelBridge(qa_model_file,
                             w2v_cache,
                             index_path)


def get_answers(pyserini, question, num_hits, k, model_choice, index_path, w2v_cache, qa_model_file):
  candidate_passages_scores = pyserini.ranked_passages(question, num_hits, k)
  idf_json = pyserini.get_term_idf_json()
  candidate_passages = []
  tokeninzed_answer = []

  if model_choice == 'sm':

    qa_DL_model = QAModel(model_choice, index_path, w2v_cache, qa_model_file).instance

    for ps in candidate_passages_scores:
      ps_split = ps.split('\t')
      candidate_passages.append(ps_split[0])

    # TODO: for processing input data. Ideally these settings need to come in as program arguments
    # NOTE: the best model for TrecQA keeps punctuation and keeps dash-words
    flags = {
      "punctuation": "", # ignoring for now  you can {keep|remove} punctuation
      "dash_words": "" # ignoring for now. you can {keep|split} words-with-hyphens
    }
    answers_list = qa_DL_model.rerank_candidate_answers(question, candidate_passages, idf_json, flags)
    sorted_answers = sorted(answers_list, key=lambda x: x[0], reverse=True)

    for score, candidate in sorted_answers:
      tokens = TreebankWordTokenizer().tokenize(candidate.lower().split("\t")[0])
      tokeninzed_answer.append((tokens, score))
    return tokeninzed_answer

  elif model_choice == 'idf':
    for candidate in candidate_passages_scores:
      candidate_sent, score = candidate.lower().split("\t")
      tokens = TreebankWordTokenizer().tokenize(candidate_sent.lower())
      tokeninzed_answer.append((tokens, score))
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
        answer = line.split('\t')
        answer_count += 1

        answer_id = 'Q{}-A{}'.format(qid, answer_count)
        answers[qid].append((answer_id, answer, label))
        labels[qid][answer_id] = label
      prev = line

  return questions, answers, labels

def validate_input(prompt_str):
  while True:
    response = input(prompt_str).strip()
    if response and response.strip() in "12":
      return response


def choose_method(first_list, second_list, judge_file, qid, model_choice):
    print("\nAnswer list1:")
    print("*"*100)
            
    for count, cand in enumerate(first_list):
        print(count + 1, " ".join(cand[0]))

    print("\n\nAnswer list2:")
    print("*"*100)
    for count, cand in enumerate(second_list):
        print(count + 1, " ".join(cand[0]))

    preference = (int(validate_input("\nWhat ranked list do you prefer[1/2]:")) + model_choice) % 2
    judge_file.write("{}\t{}\n".format(qid, pref_dict[preference]))
        
if __name__ == "__main__":
  parser = argparse.ArgumentParser(description='Evaluate the QA system')
  parser.add_argument('-input', help='path of a TrecQA file', required=True)
  parser.add_argument("-output", help="path of output file")
  parser.add_argument("-qrel", help="path of qrel file")
  parser.add_argument("-hits", help="number of hits", default=200)
  parser.add_argument("-k", help="top-k passages", default=5)
  parser.add_argument('-index', help="path of the index", required=True)
  parser.add_argument('-w2v-cache', help="word embeddings cache file", required=True)
  parser.add_argument('-qa-model-file', help="the path to the model file", required=True)
  
  args = parser.parse_args()
  judge_name = input("Enter your name:\n")

  pyserini = Pyserini(args.index)
  questions, answers, labels_actual = load_data(args.input)

  judgement_file = 'judgement.{}.TrecQa.h{}.k{}.txt'.format(judge_name, args.hits, args.k)

  last_qid = ""
  if os.path.exists(judgement_file):
        print("You have assessed a few topics already")
        lines = open(judgement_file).readlines()
        try:
          last_qid = lines[-1].split()[0]
        except IndexError as e:
          print("Empty file")

  seen_docs = []

  with open(judgement_file, 'a') as out:
    for qid, question in zip(answers.keys(), questions):

      if last_qid and qid != last_qid:
        continue
      elif qid == last_qid:
        last_qid = ""
      else:
        print("\nQuestion: {}".format(question))
        model_choice = random.randint(0, 1)
        candidates_sm = get_answers(pyserini, question, int(args.hits), int(args.k), 'sm', args.index, args.w2v_cache, \
                                    args.qa_model_file)

        candidates_idf = get_answers(pyserini, question, int(args.hits), int(args.k), 'idf', args.index, args.w2v_cache, \
                                     args.qa_model_file)

        if model_choice == 0:
          choose_method(candidates_sm, candidates_idf, out, qid, model_choice)
        else:
          choose_method(candidates_idf, candidates_sm, out, qid, model_choice)

          

        
