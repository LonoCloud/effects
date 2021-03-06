; Copyright (c) Jim Duey. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
; which can be found in the file epl-v10.html at the root of this
; distribution. By using this software in any fashion, you are
; agreeing to be bound by the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns effects
  (:refer-clojure :exclude [extend for seq]))

(defprotocol Monoid
  (zero [_])
  (plus* [_ _]))

(defn plus [mv & mvs]
  (plus* mv mvs))

(defprotocol Effects
  (lift [effect fn]))

(defprotocol EndoFunctor
  (fmap [v f]))

(defprotocol Applicative
  (wrap [x v])
  (fapply* [wrapped-f args]))

(defprotocol Traversable
  (traverse [coll f]))

(defn fapply [f arg & args]
  (fapply* (wrap arg f) (cons arg args)))

(defprotocol Monad
  (flat-map [mval func]))

(defprotocol Comonad
  (extract [wv])
  (extend [wv f]))

;; basic arrow protocol
(defprotocol Arrow
  (arrow-arr [_ f])
  (arrow-seq [p ps])
  (arrow-nth [p n]))

#_(defn seq [& ps]
  (arrow-seq (first ps) (rest ps)))

;; effecient parallel protocol
(defprotocol ArrowPar
  (arrow-par [p ps]))

;; arrow with choice protocol
(defprotocol ArrowChoice
  (arrow-select [_ vp-pairs]))

;; feedback arrow protocol
;; 'val-and-p' is a seq of with an initial value
;; and an optional feedback proc
(defprotocol ArrowLoop
  (arrow-loop [p val-and-p]))

;; identity arrow protocol
(defprotocol ArrowIdentity
  (arrow-identity [_]))

(defn comprehend [f mvs]
  (let [fmv (first mvs)
        rest-steps (reduce (fn [steps mv]
                             (fn [acc x]
                               (flat-map mv (partial steps (conj acc x)))))
                           (fn [acc x]
                             (wrap fmv (apply f (conj acc x))))
                           (reverse (rest mvs)))]
    (flat-map fmv (partial rest-steps []))))

#_(extend-type Object
  Functor
  (fmap [v f]
    (cond
     (satisfies? Monad v) (flat-map v #(wrap v (f %)))
     (satisfies? Comonad v) (extend v #(f (extract %)))
     (satisfies? Applicative v) (fapply* (wrap v f) v)
     :else (throw (Exception. (str v " does not implement 'fmap'")))))

  Applicative
  (fapply* [wrapped-f args]
    (prn :wrapped-f wrapped-f (satisfies? Monad wrapped-f))
    (cond
     (satisfies? Monad wrapped-f) (flat-map wrapped-f #(comprehend % args))
     :else (throw (Exception. (str wrapped-f " does not implement 'fapply'")))))
  )

(defmacro for [bindings expr]
  (let [steps (rest (partition 2 bindings))
        val-sym (gensym "for_")]
    `(let [~val-sym ~(second bindings)]
       (effects/flat-map ~val-sym
                         (fn [~(first bindings)]
                           ~(reduce (fn [expr [sym mv]]
                                      (cond
                                       (= :when sym) `(if ~mv
                                                        ~expr
                                                        (effects/zero ~val-sym))
                                       (= :let sym) `(let ~mv
                                                       ~expr)
                                       :else `(effects/flat-map ~mv (fn [~sym]
                                                                      ~expr))))
                                    `(effects/wrap ~val-sym ~expr)
                                    (reverse steps)))))))
