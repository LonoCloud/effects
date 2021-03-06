(ns effects.test.parser
  (:refer-clojure :exclude [for seq extend])
  (:require [effects :refer :all]
            [effects.free :refer :all]
            [effects.maybe :refer :all]
            [effects.state :refer :all :exclude [set-val update-state]])
  (:import [effects.free Pure Free FreeA FreePlus FreeZero]))

(deftype Opt [expr])
(defn optional [expr]
  (free (Opt. expr)))

(deftype NoneOrMore [expr])
(defn none-or-more [parser]
  (free (NoneOrMore. parser)))

(deftype Ignore [expr])
(defn ignore [expr]
  (free (Ignore. expr)))

(deftype Rule [name expr handler-fn])
(defmacro defrule
  ([sym]
     `(def ~sym (effects.free/free (->Rule '~sym nil nil))))
  ([sym rule]
     `(let [~sym (effects.free/free (->Rule '~sym nil nil))]
        (def ~sym (effects.free/free (->Rule '~sym ~rule nil)))))
  ([sym rule handler-fn]
     `(let [~sym (effects.free/free (->Rule '~sym nil nil))]
        (def ~sym (effects.free/free (->Rule '~sym ~rule ~handler-fn))))))


(defn term [v]
  (pure (str v)))

(defn all [& parsers]
  (fapply* (pure concat) parsers))

(defn one-of [& parsers]
  (free-plus parsers))


(defn one-or-more [parser]
  (all parser (none-or-more parser)))


(defrule upper-case (apply one-of (map term "ABCDEFGHIJKLMNOPQRSTUVWXYZ")))
(defrule lower-case (apply one-of (map term "abcdefghijklmnopqrstuvwxyz")))
(defrule letter (one-of upper-case lower-case))

(defrule integer (all (optional (one-of (term \+) (term \-)))
                      (apply one-of (map term "123456789"))
                      (none-or-more (apply one-of (map term "0123456789"))))
  (fn [& digits]
    (read-string (apply str digits))))

(defrule whitespace (apply one-of (map term " \n\t\f")))

(defrule word (one-or-more letter)
  (fn [& chars]
    (->> chars
         (apply str)
         symbol)))

(defrule form)
(defrule s-expr (all (ignore (term \())
                     (ignore (none-or-more whitespace))
                     (none-or-more
                      (all form
                           (ignore (none-or-more whitespace))))
                     (ignore (term \))))
  list)

(defrule form
  (one-of integer
          word
          s-expr))


(def state-maybe (plus state maybe))

(defn update-state [f]
  (->State maybe
           (fn [s] (maybe (list s (f s))))
           (pr-str "update-state")))

(defn set-val [k v]
  (update-state #(assoc % k v)))


(defprotocol GenParser
  (gen-parser [_]))

(defn pure-parser [term]
  (if (fn? term)
      (state-maybe term)
      (let [term-len (count term)]
        (for [text (update-state identity)
              :when (.startsWith text term)
              _ (update-state #(subs % term-len))]
          [term]))))

(extend-type Opt
  GenParser
  (gen-parser [p]
    (plus (evaluate (.expr p) pure-parser gen-parser)
          (state-maybe []))))

(defn repeat-parser [parser]
  (for [x parser
        xs (plus (repeat-parser parser)
                 (state-maybe []))]
    (concat x xs)))

(extend-type NoneOrMore
  GenParser
  (gen-parser [p]
    (plus (repeat-parser (evaluate (.expr p) pure-parser gen-parser))
          (state-maybe []))))

(extend-type Rule
  GenParser
  (gen-parser [p]
    (let [expr (.expr p)
          handler-fn (or (.handler-fn p)
                         identity)]
      (if (nil? expr)
        (for [parsed (evaluate (deref (resolve (symbol (.name p))))
                               pure-parser gen-parser)]
          [(apply handler-fn parsed)])
        (for [parsed (evaluate expr pure-parser gen-parser)]
          [(apply handler-fn parsed)])))))

(extend-type Ignore
  GenParser
  (gen-parser [p]
    (fapply (constantly []) (evaluate (.expr p) pure-parser gen-parser))))

(defn parser [expr]
  (evaluate expr pure-parser gen-parser))


(defprotocol GenEBNF
  (gen-ebnf [_]))

(deftype EBNF [x]
  Applicative
  (fapply* [_ args]
    (EBNF. (fapply* (state-maybe (fn [& args]
                                   (apply str (interpose " , " args))))
                    (map #(.x %) args))))

  Monoid
  (plus* [v vs]
    (EBNF. (fapply* (state-maybe (fn [& args]
                                   (apply str (interpose " | " args))))
                    (map #(.x %) (cons v vs))))))


(defn pure-ebnf [v]
  (EBNF. (state-maybe (condp = v
                        "\n" "\"\\n\""
                        "\t" "\"\\t\""
                        " " "\" \""
                        "\f" "\"\\f\""
                        (format "\"%s\"" v)))))


(extend-type Opt
  GenEBNF
  (gen-ebnf [p]
    (EBNF. (for [expr-ebnf (.x (evaluate (.expr p) pure-ebnf gen-ebnf))]
             (str "[ " expr-ebnf " ]")))))

(extend-type NoneOrMore
  GenEBNF
  (gen-ebnf [p]
    (EBNF. (for [expr-ebnf (.x (evaluate (.expr p) pure-ebnf gen-ebnf))]
             (str "{ " expr-ebnf " }")))))

(extend-type Rule
  GenEBNF
  (gen-ebnf [p]
    (if (.expr p)
      (EBNF. (for [expr-ebnf (.x (evaluate (.expr p) pure-ebnf gen-ebnf))
                   _ (set-val (.name p) (str expr-ebnf " ;"))]
               (.name p)))
      (EBNF. (state-maybe (.name p))))))

(extend-type Ignore
  GenEBNF
  (gen-ebnf [p]
    (evaluate (.expr p) pure-ebnf gen-ebnf)))


(defn ebnf [rule]
  (second (extract ((.x (evaluate rule pure-ebnf gen-ebnf)) {}))))

(defn print-ebnf [rule]
  (doseq [[name rule] (reverse (ebnf rule))]
    (println name ":=" rule)))



(defprotocol GenString
  (gen-string [_ depth]))

(deftype SampleString [x]
  Applicative
  (wrap [_ v] (SampleString. v))
  (fapply* [_ args]
    (SampleString. (apply str (map #(.x %) args))))

  Monoid
  (plus* [v vs]
    (nth (cons v vs) (rand-int (inc (count vs))))))


(defn pure-string [v]
  (SampleString. v))

(extend-type Opt
  GenString
  (gen-string [p depth]
    (if (or (> depth 600)
            (= 0 (rand-int 3)))
      (SampleString. "")
      (evaluate (.expr p) pure-string #(gen-string % (inc depth))))))

(extend-type NoneOrMore
  GenString
  (gen-string [p depth]
    (if (or (> depth 600)
            (= 0 (rand-int 2)))
      (SampleString. "")
      (fapply str
              (evaluate (.expr p) pure-string #(gen-string % (inc depth)))
              (gen-string p (inc depth))))))

(extend-type Rule
  GenString
  (gen-string [p depth]
    (let [expr (or (.expr p)
                   (deref (resolve (symbol (.name p)))))]
      (evaluate expr pure-string #(gen-string % (inc depth))))))

(extend-type Ignore
  GenString
  (gen-string [p depth]
    (evaluate (.expr p) pure-string #(gen-string % (inc depth)))))


(defn sample-string [rule]
  (.x (evaluate rule pure-string #(gen-string % 1))))


#_(println)
#_(print-ebnf form)

#_(println)
#_(prn (extract ((parser form) "(str ( add 15 92 ) )")))

#_(println)
#_(prn -2 (extract ((parser form) "-2")))
(dotimes [_ 100]
  (let [s (sample-string form)
        p (extract ((parser form) s))]
    (if (nil? p)
      (prn s))))
