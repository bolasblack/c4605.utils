(ns utils.async
  #?(:cljs (:require-macros
            [cljs.core.async]
            [utils.async :refer [go go-loop go-let <! <p!]]))
  (:require
   #?(:cljs [goog.object :as go])
   #?(:cljs ["util" :refer [promisify]])
   [clojure.string :as s]
   [clojure.core.async.impl.protocols]
   [clojure.core.async
    :as async
    :refer [>! take! put! close!]]
   [cats.monad.either :as ce]
   [utils.core :as uc]))

#?(:clj (defmacro go [& body]
          `(uc/if-cljs
            (cljs.core.async/go ~@body)
            (clojure.core.async/go ~@body))))

#?(:clj (defmacro go-loop [binding & body]
          `(uc/if-cljs
            (cljs.core.async/go-loop ~binding ~@body)
            (clojure.core.async/go-loop ~binding ~@body))))

#?(:clj (defmacro <! [ch]
          `(uc/if-cljs
            (cljs.core.async/<! ~ch)
            (clojure.core.async/<! ~ch))))

#?(:clj (defmacro go-let [binding & body]
          `(go (let ~binding ~@body))))

#?(:clj (defmacro go-try [& body]
          `(go (try
                 ~@body
                 (catch (uc/if-cljs js/Error Throwable) e#
                   (unpack-error e#))))))

#?(:clj (defmacro go-try-let [binding & body]
          `(go-try (let ~binding ~@body))))



(defn chan? [a]
  (satisfies? clojure.core.async.impl.protocols/ReadPort a))

(defn promise? [obj]
  (and obj (fn? (.-then obj))))



(def default-error-policy :error)

(defn error?
  "Detect if `obj` is error, available policy:

  * `:error`: (default) Expect `obj` is normal value, return true if `obj` is Error(js)/Exception(java)
  * `:node`: Expect `obj` is `coll?`, return true if `(some? (first obj))`
  * `:cats-either`: Expect `obj` is `cats.monad.either/Either`, return true if `(cats.monad.either/left? obj)`"
  [obj & opts]
  (let [{:keys [policy]
         :or {policy default-error-policy}}
        (if (and (= 1 (count opts))
                 (map? (first opts)))
          (first opts)
          (apply hash-map opts))]
    (condp = policy
      :error (uc/error? obj)
      :node (some? (first obj))
      :cats-either (ce/left? obj)
      (uc/error! (str "Unsupported policy: " policy)))))

(defn packed-error? [o]
  (let [data (ex-data o)]
    (and data
         (map? data)
         (::packed-error? data))))

(defn pack-error [obj & {:keys [policy]
                         :or {policy default-error-policy}}]
  (condp = policy
    :error
    (cond (uc/error? obj) obj
          (string? obj) (uc/error obj)
          :else (ex-info (.toString obj) {:reason obj
                                          ::packed-error? true}))

    :node
    [obj nil]

    :cats-either
    (ce/left obj)

    (uc/error! (str "Unsupported policy: " policy))))

(defn pack-value [obj & {:keys [policy]
                         :or {policy default-error-policy}}]
  (condp = policy
    :error
    obj

    :node
    [nil obj]

    :cats-either
    (ce/right obj)

    (uc/error! (str "Unsupported policy: " policy))))

(defn unpack-value [obj & {:keys [policy]
                           :or {policy default-error-policy}}]
  (condp = policy
    :error
    obj

    :node
    (and obj
         (nth obj 1 nil))

    :cats-either
    (when (ce/right? obj)
      @obj)

    (uc/error! (str "Unsupported policy: " policy))))

(defn unpack-error [obj & {:keys [policy]
                           :or {policy default-error-policy}}]
  (condp = policy
    :error
    (if (packed-error? obj)
      (:reason (ex-data obj))
      obj)

    :node
    (and obj
         (nth obj 0 nil))

    :cats-either
    (when (ce/left? obj)
      @obj)

    (uc/error! (str "Unsupported policy: " policy))))



(defn throw-err [e & error?-opts]
  (if (apply error? e error?-opts)
    (throw (pack-error e))
    e))

#?(:clj (defmacro <? [ch & error?-opts]
          `(throw-err (<! ~ch) ~@error?-opts)))



#?(:cljs
   (defn promise->chan
     "Transform `js/Promise` to `cljs.core.async/chan`, wrap error
  with `(pack-error % :policy :error)` if not reject with `js/Error`"
     [promise]
     (let [chan (async/chan)]
       (.then
        promise
        #(put! chan %1 (fn [] (close! chan)))
        #(let [err (if (uc/error? %1)
                     %1
                     (pack-error %1 :policy :error))]
           (put! chan err (fn [] (close! chan)))))
       chan)))

#?(:cljs
   (defn chan->promise
     "Resolve `cljs.core.async/chan` next value to `js/Promise`,
  reject the unpacked result if `error?`"
     [chan & {:keys [policy]
              :or {policy default-error-policy}}]
     (js/Promise.
      (fn [resolve reject]
        (go-let [val (<! chan)]
          (if (error? val :policy policy)
            (reject (unpack-error val :policy policy))
            (resolve (unpack-value val :policy policy))))))))

#?(:clj (defmacro <p! [promise]
          `(<! (promise->chan ~promise))))

#?(:clj (defmacro <p? [promise & error?-opts]
          `(<? (promise->chan ~promise) ~@error?-opts)))



#?(:cljs
   (defn chan->async-iterator
     "Transform `cljs.core.async/chan` to AsyncIterator"
     [chan & {:keys [policy convert-to-js]
              :or {policy default-error-policy
                   convert-to-js false}}]
     (let [res #js {:next (fn [] (.then (chan->promise chan :policy policy)
                                       (fn [res]
                                         #js {:done (nil? res)
                                              :value (if convert-to-js
                                                       (if (some? res)
                                                         (clj->js res)
                                                         js/undefined)
                                                       res)})))}]
       (try (go/set res js/Symbol.asyncIterator (fn [] res)))
       res)))



(defn flat-chan [ch]
  (go-loop [c ch]
    (if (chan? c)
      (recur (<! c))
      c)))

#?(:clj (defmacro <<! [ch]
          `(<! (flat-chan ~ch))))

#?(:clj (defmacro <<? [ch & error?-opts]
          `(<? (flat-chan ~ch) ~@error?-opts)))



(defn limit-map [f source limit-chan]
  (let [src-chan (if (chan? source)
                   source
                   (async/to-chan source))
        dst-chan (async/chan)
        wait-all-put-chan (volatile! (async/to-chan [:start]))]
    (go-loop []
      (<! limit-chan)
      (if-let [data (<! src-chan)]
        (let [r (f data)]
          (if (chan? r)
            (vswap! wait-all-put-chan
                    (fn [old-chan]
                      (go-let [resp (<! r)]
                        (<! old-chan)
                        (>! dst-chan resp))))
            (vswap! wait-all-put-chan
                    (fn [old-chan]
                      (go (<! old-chan)
                          (>! dst-chan r)))))
          (recur))
        (do (<! @wait-all-put-chan)
            (close! dst-chan))))
    dst-chan))



(defn chan->vec [chan]
  (async/reduce (fn [memo item] (concat memo [item])) [] chan))



#?(:cljs
   (defn denodify
     "Returns a function that will wrap the given `nodeFunction`.
  Instead of taking a callback, the returned function will return
  a `cljs.core.async/chan` whose fate is decided by the callback
  behavior of the given node function. The node function should
  conform to node.js convention of accepting a callback as last
  argument and calling that callback with error as the first
  argument and success value on the second argument.

  If the `nodeFunction` calls its callback with multiple success
  values, the fulfillment value will be an array of them.

  If you pass a `receiver`, the `nodeFunction` will be called as a
  method on the `receiver`.

  Example of promisifying the asynchronous `readFile` of node.js `fs`-module:

  ```clojurescript
  (def read-file (denodify (.-readFile fs)))

  (go (try
        (let [content (<? (read-file \"myfile\" \"utf8\") :policy :node)]
          (println \"The result of evaluating myfile.js\" (.toString content)))
        (catch js/Error err
          (prn 'Error reading file' err))))
  ```

  Note that if the node function is a method of some object, you
  can pass the object as the second argument like so:

  ```clojurescript
  (def redis-get (denodify (.-get redisClient) redisClient))

  (go (<! (redis-get \"foo\")))
  ```
  "
     ([f]
      (denodify f nil))
     ([f receiver]
      (let [promisify-fn (promisify f)
            denodified-fn (fn denodified-fn [& args]
                            (go (<p! (.apply promisify-fn receiver (apply array args)))))]
        (try
          (js/Object.defineProperty
           denodified-fn
           "length"
           #js {:configurable true :value (if (zero? (.-length promisify-fn))
                                            0
                                            (dec (.-length promisify-fn)))})
          (let [new-name (if (s/blank? (.-name f))
                           "denodified_fn"
                           (str "denodified_" (.-name f)))]
            (js/Object.defineProperty
             denodified-fn
             "name"
             #js {:configurable true :value new-name}))
          (catch js/Error err
            (js/console.error err)))
        denodified-fn))))
