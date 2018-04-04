(ns utils.async-test
  (:refer-clojure :exclude [map])
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [pjstadig.humane-test-output]
            [goog.object :as go]
            [cljs.test :as ct :refer-macros [deftest testing is] :include-macros true]
            [cljs.core.async :as async :refer [<! >! put!]]
            [cats.monad.either :as ce]
            [utils.async :as ua :include-macros true]
            ["lodash.isequal" :as js-equal]))




(deftest chan?
  (is (not (ua/chan? [])))
  (is (ua/chan? (async/chan))))




(deftest promise?
  (is (not (ua/promise? [])))
  (is (ua/promise? (js/Promise.resolve 1))))




(deftest error?
  (is (ua/error? (js/Error.)))
  (is (ua/error? (js/Error.) :policy :error))

  (is (not (ua/error? [] :policy :node)))
  (is (not (ua/error? [nil] :policy :node)))
  (is (ua/error? [(js/Error.)] :policy :node))
  (is (ua/error? [""] :policy :node))

  (is (not (ua/error? "" :policy :cats-either)))
  (is (not (ua/error? (ce/right 1) :policy :cats-either)))
  (is (not (ua/error? (ce/right (js/Error.)) :policy :cats-either)))
  (is (ua/error? (ce/left 1) :policy :cats-either))

  (try
    (ua/error? "" :policy :unknown)
    (is false)
    (catch js/Error err
      (is err.message "Unsupported policy: :unknown"))))




(deftest limit-map
  (ct/async
   done
   (ua/go-let [limit-chan (async/chan 5)
               last-job-info (volatile! nil)
               last-job-result (volatile! nil)
               job-ids (range 10)
               map-chan (ua/limit-map
                         #(do (vreset! last-job-info %)
                              (* 2 %))
                         job-ids
                         limit-chan)]

     (ua/go-loop []
       (let [r (<! map-chan)]
         (vreset! last-job-result r)
         (recur)))

     (is (= nil @last-job-info))
     (is (= nil @last-job-result))

     (dotimes [n 5]
       (>! limit-chan n))
     (<! (async/timeout 0))
     (is (= 4 @last-job-info))
     (is (= 8 @last-job-result))

     (<! (async/timeout 0))
     (is (= 4 @last-job-info))
     (is (= 8 @last-job-result))

     (dotimes [n 2]
       (>! limit-chan n))
     (<! (async/timeout 0))
     (is (= 6 @last-job-info))
     (is (= 12 @last-job-result))

     (done))))




(deftest pack-value
  (let [err (js/Error. "test")]
    (is (= 1 (ua/pack-value 1)))

    (is (= 1 (ua/pack-value 1 :policy :error)))
    (is (= err (ua/pack-value err :policy :error)))

    (is (= [nil 1] (ua/pack-value 1 :policy :node)))
    (is (= [nil err] (ua/pack-value err :policy :node)))

    (is (= (ce/right 1) (ua/pack-value 1 :policy :cats-either)))
    (is (= (ce/right err) (ua/pack-value err :policy :cats-either)))))




(deftest pack-error
  (let [err (js/Error. "test")]
    (let [ex (ua/pack-error 1)]
      (is (= "1" (.-message ex)))
      (is (= {:reason 1} (ex-data ex))))

    (let [ex (ua/pack-error 1 :policy :error)]
      (is (= "1" (.-message ex)))
      (is (= {:reason 1} (ex-data ex))))
    (let [ex (ua/pack-error err :policy :error)]
      (is (identical? ex err)))

    (is (= [1 nil] (ua/pack-error 1 :policy :node)))
    (is (= [err nil] (ua/pack-error err :policy :node)))

    (is (= (ce/left 1) (ua/pack-error 1 :policy :cats-either)))
    (is (= (ce/left err) (ua/pack-error err :policy :cats-either)))))




(deftest unpack-value
  (let [err (js/Error. "test")]
    (is (= 1 (ua/unpack-value 1)))
    (is (= nil (ua/unpack-value nil)))

    (is (= 1 (ua/unpack-value 1 :policy :error)))
    (is (= err (ua/unpack-value err :policy :error)))
    (is (= nil (ua/unpack-value nil :policy :error)))

    (is (= 1 (ua/unpack-value [nil 1] :policy :node)))
    (is (= err (ua/unpack-value [nil err] :policy :node)))
    (is (= nil (ua/unpack-value [1 nil] :policy :node)))
    (is (= nil (ua/unpack-value [1] :policy :node)))
    (is (= nil (ua/unpack-value nil :policy :node)))

    (is (= 1 (ua/unpack-value (ce/right 1) :policy :cats-either)))
    (is (= err (ua/unpack-value (ce/right err) :policy :cats-either)))
    (is (= nil (ua/unpack-value (ce/left 1) :policy :cats-either)))
    (is (= nil (ua/unpack-value nil :policy :cats-either)))))




(deftest unpack-error
  (let [err (js/Error. "test")]
    (is (= 1 (ua/unpack-error (ex-info "" {:reason 1}))))
    (is (= err (ua/unpack-error err)))
    (is (= nil (ua/unpack-error nil)))

    (is (= 1 (ua/unpack-error (ex-info "" {:reason 1}) :policy :error)))
    (is (= err (ua/unpack-error err :policy :error)))
    (is (= nil (ua/unpack-error nil :policy :error)))

    (is (= 1 (ua/unpack-error [1 nil] :policy :node)))
    (is (= 1 (ua/unpack-error [1] :policy :node)))
    (is (= err (ua/unpack-error [err nil] :policy :node)))
    (is (= err (ua/unpack-error [err] :policy :node)))
    (is (= nil (ua/unpack-error [nil 1] :policy :node)))
    (is (= nil (ua/unpack-error [] :policy :node)))
    (is (= nil (ua/unpack-error nil :policy :node)))

    (is (= 1 (ua/unpack-error (ce/left 1) :policy :cats-either)))
    (is (= err (ua/unpack-error (ce/left err) :policy :cats-either)))
    (is (= nil (ua/unpack-error (ce/right err) :policy :cats-either)))
    (is (= nil (ua/unpack-error nil :policy :cats-either)))))




(deftest promise->chan
  (ct/async
   done
   (ua/go-let [fake-error (js/Error. "fake error")
               r1 (<! (ua/promise->chan (js/Promise.resolve 1)))
               r2 (<! (ua/promise->chan (js/Promise.reject 2)))
               r3 (<! (ua/promise->chan (js/Promise.reject fake-error)))]
     (is (= 1 r1))
     (is (= {:reason 2} (ex-data r2)))
     (is (= fake-error r3))
     (done))))

(deftest chan->promise
  (ct/async
   done
   (let [fake-error (js/Error. "fake error")
         resolve-result (fn [promise]
                          (.then
                           promise
                           (fn [val] {:type :resolve :val val})
                           (fn [val] {:type :reject :val val})))
         ps [(resolve-result (ua/chan->promise (go 1)))
             (resolve-result (ua/chan->promise (go fake-error)))
             (resolve-result (ua/chan->promise (go 1) :policy :error))
             (resolve-result (ua/chan->promise (go fake-error) :policy :error))
             (resolve-result (ua/chan->promise (go [nil 1]) :policy :node))
             (resolve-result (ua/chan->promise (go [1 nil]) :policy :node))
             (resolve-result (ua/chan->promise (go (ce/right 1)) :policy :cats-either))
             (resolve-result (ua/chan->promise (go (ce/left 1)) :policy :cats-either))]
         final-promise (js/Promise.all (clj->js ps))]
     (.then
      final-promise
      (fn [rs]
        (is (= [{:type :resolve :val 1}
                {:type :reject :val fake-error}
                {:type :resolve :val 1}
                {:type :reject :val fake-error}
                {:type :resolve :val 1}
                {:type :reject :val 1}
                {:type :resolve :val 1}
                {:type :reject :val 1}]
               (js->clj rs)))
        (done))
      (fn [err]
        (is false)
        (done))))))

(deftest <p!
  (let [fake-error (js/Error.)
        resp (macroexpand-1 '(ua/<p! promise))]
    (is (= '(utils.async/<! (utils.async/promise->chan promise))
           resp))))

(deftest <p?
  (let [fake-error (js/Error.)
        r1 (macroexpand-1 '(ua/<p? promise))
        r2 (macroexpand-1 '(ua/<p? promise :policy :node))]
    (is (= '(utils.async/<? (utils.async/promise->chan promise))
           r1))
    (is (= '(utils.async/<? (utils.async/promise->chan promise) :policy :node)
           r2))))



(defn- is-async-iterator [obj]
  (let [res-fn (go/get obj js/Symbol.asyncIterator)
        res (res-fn)
        _ (is (fn? res-fn))
        _ (is (= res obj))
        _ (is (fn? (.-next obj)))]))

(defn- next-async-iterator [next done value]
  (ua/go-let [_ (is (ua/promise? next))
              res (ua/<p! next)
              _ (is (= done (.-done res)))
              _ (is (js-equal value (.-value res)))]))

(deftest chan->async-iterator
  (ct/async
   done
   (ua/go-let [iterator (ua/chan->async-iterator (async/to-chan (range 3)))
               _ (is-async-iterator iterator)
               _ (<! (next-async-iterator (.next iterator) false 0))
               _ (<! (next-async-iterator (.next iterator) false 1))
               _ (<! (next-async-iterator (.next iterator) false 2))
               _ (<! (next-async-iterator (.next iterator) true nil))]
     (done))))

(deftest chan->async-iterator--convert-to-js
  (ct/async
   done
   (ua/go-let [iterator (ua/chan->async-iterator
                         (async/to-chan
                          [{:a 1}
                           {:a 2}])
                         :convert-to-js true)
               _ (is-async-iterator iterator)
               _ (<! (next-async-iterator (.next iterator) false #js {:a 1}))
               _ (<! (next-async-iterator (.next iterator) false #js {:a 2}))
               _ (<! (next-async-iterator (.next iterator) true js/undefined))]
     (done))))

(deftest chan->async-iterator--with-error
  (ct/async
   done
   (ua/go-let [fake-error (js/Error. "test error")
               iterator (ua/chan->async-iterator (ua/go fake-error) :policy :error)
               _ (is-async-iterator iterator)

               next (.next iterator)
               _ (is (ua/promise? next))]
     (.then
      next
      (fn [data]
        (is false)
        (done))
      (fn [err]
        (go (is (= err fake-error))
            (<! (next-async-iterator (.next iterator) true nil))
            (done)))))))




(defn- close-to? [expected actual &
                  {:keys [deviate]
                   :or {deviate 100}}]
  (some #(= actual %)
        (range (- expected deviate)
               (+ expected deviate))))

(defn- create-chan [duration & args]
  (let [chan (async/chan)]
    (go (<! (async/timeout duration))
        (>! chan (into [1] args))
        (<! (async/timeout duration))
        (>! chan (into [2] args))
        (<! (async/timeout duration))
        (>! chan (into [3] args))
        (async/close! chan))
    chan))

(deftest wait-multiple-chan
  (ct/async
   done
   (ua/go-let
     [start (js/Date.now)
      chan (async/map
            #(conj %& (- (js/Date.now) start))
            [(create-chan 100 :a1 :a2)
             (create-chan 200 :b1 :b2)])

      d1 (<! chan)
      _ (is (close-to? 200 (first d1)))
      _ (is (= (next d1)
               '([1 :a1 :a2]
                 [1 :b1 :b2])))

      d2 (<! chan)
      _ (is (close-to? 400 (first d2)))
      _ (is (= (next d2)
               '([2 :a1 :a2]
                 [2 :b1 :b2])))

      d3 (<! chan)
      _ (is (close-to? 600 (first d3)))
      _ (is (= (next d3)
               '([3 :a1 :a2]
                 [3 :b1 :b2])))]

     (done))))




(deftest go-try-test
  (ct/async
   done
   (ua/go-let [e (ex-info "foo" {})
               ch (async/chan)]
     (>! ch e)
     (is (= e (<! (ua/go-try
                   (ua/<? ch)
                   :invalid-resp))))
     (ua/go-try
      (try
        (ua/<? ch)
        (catch js/Error err
          (is (= e err)))))
     (done))))

(deftest go-try-test-with-non-standard-error
  (ct/async
   done
   (ua/go-let [e [123 nil]
               ch (async/chan)]
     (>! ch e)
     (is (= e (<! (ua/go-try
                   (ua/<? ch :policy :node)
                   :invalid-resp))))
     (>! ch e)
     (ua/go-try
      (try
        (ua/<? ch :policy :node)
        (catch js/Error err
          (is (= {:ua/from-<? true
                  :original [123 nil]})))))
     (done))))




(defn read-both [ch-a ch-b]
  (ua/go-try
   (let [a (ua/<? ch-a)
         b (ua/<? ch-b)]
     [a b])))

(deftest read-both-test-1
  (ct/async
   done
   (ua/go-let [e (ex-info "foo" {})
               ch-a (async/chan)
               ch-b (async/chan)]
     (put! ch-a e)
     (is (= e (<! (read-both ch-a ch-b))))
     (done))))

(deftest read-both-test-2
  (ct/async
   done
   (ua/go-let [e (ex-info "foo" {})
               ch-a (async/chan)
               ch-b (async/chan)]
     (put! ch-a 1)
     (put! ch-b e)
     (is (= e (<! (read-both ch-a ch-b))))
     (done))))

(deftest read-both-test-3
  (ct/async
   done
   (ua/go-let [e (ex-info "foo" {})
               ch-a (async/chan)
               ch-b (async/chan)]
     (put! ch-a e)
     (put! ch-b 1)
     (read-both ch-a ch-b)
     (is (= 1 (<! ch-b)))
     (done))))




(deftest flat-chan
  (ct/async
   done
   (ua/go-let [chan1 (go (go (go 1)))
               chan2 (go 1)
               chan3 1]
     (is (= 1 (<! (ua/flat-chan chan1))))
     (is (= 1 (<! (ua/flat-chan chan2))))
     (is (= 1 (<! (ua/flat-chan chan3))))
     (done))))

(deftest <<!
  (let [res (macroexpand-1 '(ua/<<! chan))]
    (is (= '(ua/<! (ua/flat-chan chan)) res))))

(deftest <<?
  (let [r1 (macroexpand-1 '(ua/<<? chan))
        r2 (macroexpand-1 '(ua/<<? chan :policy :node))]
    (is (= '(ua/<? (ua/flat-chan chan))
           r1))
    (is (= '(ua/<? (ua/flat-chan chan) :policy :node)
           r2))))




(deftest chan->vec
  (ct/async
   done
   (ua/go-let [chan (async/to-chan [1 2 3])
               resp (<! (ua/chan->vec chan))]
     (is (= [1 2 3] resp))
     (done))))
