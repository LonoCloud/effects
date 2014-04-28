; Copyright (c) Jim Duey. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
; which can be found in the file epl-v10.html at the root of this
; distribution. By using this software in any fashion, you are
; agreeing to be bound by the terms of this license.
; You must not remove this notice, or any other, from this software.

(ns effects.free
  (:refer-clojure :exclude [extend for seq])
  (:require [effects :refer :all]))

(deftype Pure [v]
  Object
  (toString [_]
    (pr-str v))

  Applicative
  (wrap [_ v]
    (Pure. v))

  Monad
  (flat-map [_ f]
    (f v))

  Comonad
  (extract [_] v))

(deftype Free [val]
  Object
  (toString [_]
    (pr-str val))

  Applicative
  (wrap [_ v]
    (Pure. v))

  Monad
  (flat-map [_ f]
    (Free. (fmap val #(flat-map % f))))

  ;; Arrow
  ;; (arrow-arr [_ f]
  ;;   (fn [x]
  ;;     (Pure. (f x))))
  ;; (arrow-seq [p ps]
  ;;   (if (empty? ps)
  ;;     p
  ;;     (let [rest-p (arrow-seq (first ps) (rest ps))]
  ;;       (fn [s]
  ;;         (flat-map (p s) rest-p)))))
  ;; (arrow-nth [ev n]
  ;;   )

  Comonad
  (extract [_]
    val))

(defn liftF [f-val]
  (Free. (fmap f-val (fn [x] (Pure. x)))))


(deftype FreeT [e v]
  Object
  (equals [x y]
    (and (= (class x) (class y))
         (= v (extract y))))
  (toString [_]
    (pr-str v))

  Applicative
  (wrap [_ v]
    (Pure. v))

  Monad
  (flat-map [ev f]
    (FreeT. e (flat-map v (fn [x]
                            (e (fmap x (fn [ev]
                                         (flat-map ev f))))))))

  MonadZero
  (zero [_]
    (FreeT. e (zero (e :nil))))
  (plus* [mv mvs]
    (FreeT. e
            (->> (cons mv mvs)
                 (map extract)
                 (apply plus))))

  Comonad
  (extract [_] v))

(defn liftFT [m f-val]
  (FreeT. m (m (fmap f-val (fn [x] (Pure. (m x)))))))
