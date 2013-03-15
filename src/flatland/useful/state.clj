(ns flatland.useful.state
  (:require [flatland.useful.time :as time])
  (:use [flatland.useful.utils :only [returning]])
  (:import [clojure.lang IDeref IObj]
           [java.util.concurrent ScheduledThreadPoolExecutor ThreadFactory]))

(defprotocol Mutable
  (put! [self v]))

(deftype Volatile [^{:volatile-mutable true} val validator meta]
  IDeref
  (deref [self] val)
  Mutable
  (put! [self v]
    (if (and validator (not (validator v)))
      (throw (IllegalStateException. "Invalid reference state"))
      (set! val v)))
  IObj
  (meta [self]
    meta)
  (withMeta [self meta]
    (Volatile. val validator meta)))

(defn volatile
  "Creates and returns a Volatile with an initial value of x and zero or
  more options (in any order):

  :meta metadata-map

  :validator validate-fn

  If metadata-map is supplied, it will become the metadata on the
  Volatile. validate-fn must be nil or a side-effect-free fn of one
  argument, which will be passed the intended new state on any state
  change. If the new state is unacceptable, the validate-fn should
  return false or throw an exception."
  ([x]
     (Volatile. x nil {}))
  ([x & options]
     (let [opts (apply hash-map options)]
       (Volatile. x (:validator opts) (:meta opts)))))

(defn trade!
  "Like swap!, except it returns the old value of the atom."
  [atom f & args]
  (let [m (volatile nil)]
    (apply swap! atom
           (fn [val & args]
             (put! m val)
             (apply f val args))
           args)
    @m))

(defn wait-until [reference pred]
  (let [curr @reference] ;; try to get out fast - not needed for correctness, just performance
    (if (pred curr)
      curr
      (let [result (promise)]
        (add-watch reference result
                   (fn this [_ _ old new]
                     (when (pred new)
                       (try ;; multiple delivers throw an exception in clojure 1.2
                         (when (deliver result new)
                           (remove-watch reference result))
                         (catch Exception e
                           nil)))))
        (let [curr @reference] ; needed for correctness, in case it's become acceptable since adding
                               ; watcher and will never change again
          (if (pred curr)
            (do (remove-watch reference result)
                curr)
            @result))))))

(let [executor (ScheduledThreadPoolExecutor. 1 (reify ThreadFactory
                                                 (newThread [this r]
                                                   (doto (Thread. r)
                                                     (.setDaemon true)))))]
  (defn periodic-recompute
    "Takes a thunk and a duration (from flatland.useful.time), and yields a function
   that attempts to pre-cache calls to that thunk. The first time you call
   the returned function, it starts a background thread that re-computes the
   thunk's result according to the requested duration.

   If you call the returned function with no arguments, it blocks until
   some cached value is available; with one not-found argument, it returns
   the not-found value if no cached value has yet been computed.

   Take care: if the duration you specify causes your task to be scheduled
   again while it is still running, the task will wait in a queue. That queue
   will continue to grow unless your task is able to complete more quickly
   than the duration you specified."
    [f duration]
    (let [{:keys [unit num]} duration
          cache (agent {:ready false})
          task (delay (.scheduleAtFixedRate executor
                                            (fn []
                                              (send cache
                                                    (fn [_]
                                                      {:ready true
                                                       :value (f)})))
                                            0, num unit))
          get-ready (fn [] (do @task nil))]
      (fn
        ([]
           (do (get-ready)
               (:value (wait-until cache :ready))))
        ([not-found]
           (do (get-ready)
               (let [{:keys [ready value]} @cache]
                 (if ready
                   value
                   not-found))))))))

(defmacro with-altered-vars
  "Binds each var-name to the result of (apply f current-value args) for the dynamic
  scope of body. Basically like swap! or alter, but for vars. bindings should be a
  vector, each element of which should look like a function call:

  (with-altered-vars [(+ x 10)] body) ;; binds x to (+ x 10)"
  [bindings & body]
  `(binding [~@(for [[f var-name & args] bindings
                     binding `[~var-name (~f ~var-name ~@args)]]
                 binding)]
     ~@body))

(defmacro with-altered-roots
  "Use alter-var-root to temporarily modify the root bindings of some vars.
   For each var, the temporary value will be (apply f current-value args).

   bindings should be a vector, each element of which should look like a function call:
  (with-altered-roots [(+ x 10)] body) ;; sets x to (+ x 10)

   Use with caution: this is not thread-safe, and multiple concurrent calls
   can leave vars' root values in an unpredictable state."
  [bindings & body]
  (let [inits (gensym 'init-vals)]
    `(let [~inits (atom {})]
       ~@(for [[f var-name & args] bindings]
           (let [v (gensym var-name)]
             `(alter-var-root (var ~var-name)
                              (fn [~v]
                                (swap! ~inits assoc '~var-name ~v)
                                (~f ~v ~@args)))))
       (returning (do ~@body)
         ~@(for [[f var-name & args] (reverse bindings)]
             `(alter-var-root (var ~var-name) (constantly ('~var-name @~inits))))))))
