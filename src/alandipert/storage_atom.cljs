(ns alandipert.storage-atom
  (:require [cognitect.transit :as t]
            [goog.Timer :as timer]
            [clojure.string :as string]))


(def transit-read-handlers (atom {}))

(def transit-write-handlers (atom {}))

(defn clj->json [x]
  (t/write (t/writer :json {:handlers @transit-write-handlers}) x))

(defn json->clj [x]
  (t/read (t/reader :json {:handlers @transit-read-handlers}) x))

(defprotocol IStorageBackend
  "Represents a storage resource."
  (-get [this not-found])
  (-commit! [this value] "Commit value to storage at location."))

(deftype StorageBackend [store key]
  IStorageBackend
  (-get [this not-found]
    (if-let [existing (.getItem store (clj->json key))]
      (json->clj existing)
      not-found))
  (-commit! [this value]
    (.setItem store (clj->json key) (clj->json value))))

(defn exchange! [a x]
  (let [y @a]
    (if (compare-and-set! a y x)
      y
      (recur a x))))

(defn debounce-factory
  "Return a function that will always store a future call into the
  same atom. If recalled before the time is elapsed, the call is
  replaced without being executed." []
  (let [f (atom nil)]
    (fn [func ttime]
      (let [timed-func (when-not (= ttime :none)
                         (timer/callOnce func ttime))
            old-timed-func (exchange! f timed-func)]
        (when old-timed-func
          (timer/clear old-timed-func))
        (when-not timed-func
          (func))
        nil))))

(def storage-delay
  "Delay in ms before a change is committed to the local storage. If a
new change occurs before the time is elapsed, the old change is
discarded an only the new one is committed."
  (atom 10))

(def ^:dynamic *storage-delay* nil)

(def ^:dynamic *watch-active* true)
;; To prevent a save/load loop when changing the values quickly.

(defprotocol IPartitioning
  "A set of operations that allows a value to be partitioned into two parts:
  the *storable* part that should be stored into the storage and *unstorable*
  part that shouldn't.  A typical target of the operations would be an
  application database.

  The operations are:

  - `split` (`[partitioning value]`): Splits `value` into a vector
    `[unstorable storable]`.

  - `join` (`[partitioning [unstorable storable]]`): Joins the vector
    `[unstorable storable]` back into a value representing the whole.

  An `IPartitioning` should always satisfy the following invariant for all
  meaningful values:

  ```
  (= (->> value
          (split partitioning)
          (join partitioning))
     value)
  ```"
  (split [this value]
    "Split `value` into a vector of form `[unstorable storable]`.")
  (join [this parts]
    "Join `parts`, a vector of form `[unstorable storable]`, into value
    representing the whole."))

(defn get-unstorable
  "Return the unstorable part of `value` based on `partitioning`."
  [partitioning value]
  (->> value (split partitioning) (first)))

(defn get-storable
  "Return the storable part of `value` based on `partitioning`."
  [partitioning value]
  (->> value (split partitioning) (second)))

(defn swap-storable
  "Given `partitioning` replace the storable part of `value` with `storable`
  leaving the unstorable part untouched."
  [value partitioning storable]
  (join partitioning [(get-unstorable partitioning value) storable]))

(def store-all
  "A partitioning that results in storing everything into the storage."
  (reify IPartitioning
    (split [_ value]
      [nil value])
    (join [_ [_ stored]]
      stored)))

(defn store
  [atom backend partitioning]
  (let [existing (-get backend ::none)
        debounce (debounce-factory)]
    (if (= ::none existing)
      (->> @atom (get-storable partitioning) (-commit! backend))
      (swap! atom swap-storable partitioning existing))
    (doto atom
      (add-watch ::storage-watch
                 (fn [_ _ old-value new-value]
                   (let [new-storable (get-storable partitioning new-value)]
                     (when (and *watch-active*
                                (not= (get-storable partitioning old-value)
                                      new-storable))
                       (debounce (fn []
                                   (-commit! backend new-storable))
                                 (or *storage-delay*
                                     @storage-delay)))))))))

(defn maybe-update-backend
  [atom storage k partitioning initial-storable e]
  (when (identical? storage (.-storageArea e))
    (if (empty? (.-key e)) ;; is all storage is being cleared?
      (binding [*watch-active* false]
        (swap! atom swap-storable partitioning initial-storable))
      (try
        (when-let [sk (json->clj (.-key e))]
          (when (= sk k) ;; is the stored key the one we are looking for?
            (binding [*watch-active* false]
              (swap! atom
                     swap-storable
                     partitioning
                     (let [value (.-newValue e)] ;; new value, or is key being removed?
                       (if-not (string/blank? value)
                         (json->clj value)
                         initial-storable))))))
        (catch :default e)))))

(defn link-storage
  [atom storage k partitioning]
  (let [[_ initial-storable] (split partitioning @atom)]
    (.addEventListener js/window "storage"
                       #(maybe-update-backend atom
                                              storage
                                              k
                                              partitioning
                                              initial-storable
                                              %))))

(defn dispatch-synthetic-event!
  "Create and dispatch a synthetic StorageEvent. Expects `key` to be a string
  and `value` to be a string or nil.  An empty `key` indicates that all
  storage is being cleared.  A nil or empty `value` indicates that the key is
  being removed."
  [storage key value]
  (.dispatchEvent js/window
                  (doto (.createEvent js/document "StorageEvent")
                    (.initStorageEvent "storage"
                                       false
                                       false
                                       key
                                       nil
                                       value
                                       (.. js/window -location -href)
                                       storage)))
  nil)

;;; mostly for tests

(defn load-html-storage
  [storage k]
  (-get (StorageBackend. storage k) nil))

(defn load-local-storage [k]
  (load-html-storage js/localStorage k))

(defn load-session-storage [k]
  (load-html-storage js/sessionStorage k))

;;; main API

(defn html-storage
  ([atom storage k]
   (html-storage atom storage k {}))
  ([atom storage k {:keys [partitioning] :or {partitioning store-all}}]
   (link-storage atom storage k partitioning)
   (store atom (StorageBackend. storage k) partitioning)))

(defn local-storage
  [atom & args]
  (apply html-storage atom js/localStorage args))

(defn session-storage
  [atom & args]
  (apply html-storage atom js/sessionStorage args))

;; Methods to safely remove items from storage or clear storage entirely.

(defn clear-html-storage!
  "Clear storage and also trigger an event on the current window
  so its atoms will be cleared as well."
  [storage]
  (.clear storage)
  (dispatch-synthetic-event! storage "" nil))

(defn clear-local-storage! []
  (clear-html-storage! js/localStorage))

(defn clear-session-storage! []
  (clear-html-storage! js/sessionStorage))

(defn remove-html-storage!
  "Remove key from storage and also trigger an event on the current
  window so its atoms will be cleared as well."
  [storage k]
  (let [key (clj->json k)]
    (.removeItem storage key)
    (dispatch-synthetic-event! storage key nil)))

(defn remove-local-storage! [k]
  (remove-html-storage! js/localStorage k))

(defn remove-session-storage! [k]
  (remove-html-storage! js/sessionStorage k))

;;; Some extra partitioning

(deftype MapFieldPartitioning [key]
  IPartitioning
  (split [_ value]
    (if (map? value)
      ;; NB: `::none` would conflict
      [(dissoc value key) (get value key ::unstored)]
      [value ::unstored]))
  (join [this [unstored stored]]
    (if (and (map? unstored) (not= stored ::unstored))
      (assoc unstored key stored)
      unstored)))

(defn store-map-field
  "Return a partitioning under which the `key` field of a map is regarded as
  the storable part and the rest of the map as the unstorable."
  [key]
  (MapFieldPartitioning. key))
