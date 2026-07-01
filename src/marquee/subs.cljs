(ns marquee.subs
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [martian.re-frame :as martian]
            [marquee.lib.utils :as utils]))

(defn- item-matches?
  "Case-insensitive substring match of `needle` against any of an item's
  human-readable text fields. Used for client-side media filtering."
  [needle item]
  (let [needle (str/lower-case needle)]
    (boolean
     (some (fn [v] (and v (str/includes? (str/lower-case (str v)) needle)))
           ((juxt :name :title :description :overview :kind) item)))))

(rf/reg-sub
 ::active-page
 (fn [db _]
   (:active-page db)))

;; True once every configured martian instance has finished loading its spec.
;; Also true when no services are configured yet (so the UI is usable during dev).
;; martian.re-frame stores instances at [:martian.re-frame/martian <instance-id> :m].
(rf/reg-sub
 ::api-ready?
 (fn [db _]
   (let [instances (vals (get db :martian.re-frame/martian {}))]
     (or (empty? instances)
         (every? (comp boolean :m) instances)))))

(rf/reg-sub
 ::jellyfin-url
 (fn [db _]
   (:jellyfin-url db)))

(rf/reg-sub
 ::pseudovision-url
 (fn [db _]
   (:pseudovision-url db)))

;; Media subscriptions

(rf/reg-sub
 ::media-libraries
 (fn [db _]
   (:media-libraries db)))

(rf/reg-sub
 ::current-media-id
 (fn [db _]
   (:current-media-id db)))

(rf/reg-sub
 ::media-item
 (fn [db [_ media-id]]
   (get-in db [:media-items media-id])))

;; Whole media-item cache, keyed by id. Used where we need to resolve several
;; ids at once (e.g. labelling playout events in the schedule/guide).
(rf/reg-sub
 ::media-items-map
 (fn [db _]
   (:media-items db {})))

(rf/reg-sub
 ::scheduler-metadata
 (fn [db [_ media-id]]
   (get-in db [:scheduler-metadata media-id])))

;; The chain of a media item's ancestors — immediate parent first, up to the
;; root — each paired with its loaded item, tags, and scheduler categories, so
;; the detail page can surface attributes inherited from parents (e.g. a show's
;; tags on an episode). Ancestors are cached under their Pseudovision id,
;; matching how ::load-media-ancestors stores them.
(rf/reg-sub
 ::media-ancestors
 (fn [db [_ media-id]]
   (let [items (:media-items db)]
     (loop [pid  (:parent-id (get items media-id))
            seen #{}
            acc  []]
       (let [parent (get items pid)]
         (if (and pid parent (not (contains? seen pid)))
           (recur (:parent-id parent)
                  (conj seen pid)
                  (conj acc {:id         pid
                             :item       parent
                             :tags       (get-in db [:media-tags (:id parent)] [])
                             :categories (get-in db [:media-categories pid])}))
           acc))))))

;; A media item's descriptive, ancestor-qualified name (e.g.
;; "Episode 13 - Season 2 - The Show"), resolved against the media-items cache.
(rf/reg-sub
 ::media-full-name
 (fn [db [_ media-id]]
   (let [items (:media-items db)]
     (utils/media-display-name (get items media-id) items))))

(rf/reg-sub
 ::media-tags
 (fn [db [_ numeric-id]]
   (get-in db [:media-tags numeric-id] [])))

;; Library selection and pagination subscriptions

(rf/reg-sub
 ::selected-library-id
 (fn [db _]
   (:selected-library-id db)))

(rf/reg-sub
 ::selected-library
 :<- [::media-libraries]
 :<- [::selected-library-id]
 (fn [[libraries selected-id] _]
   (when (and libraries selected-id)
     (first (filter #(= (:id %) selected-id) libraries)))))

(rf/reg-sub
 ::media-current-page
 (fn [db _]
   (:media-current-page db 1)))

(rf/reg-sub
 ::media-page-size
 (fn [db _]
   (:media-page-size db 20)))

(rf/reg-sub
 ::media-filter
 (fn [db _]
   (:media-filter db "")))

;; The current page of items for the selected library, as returned by the
;; server. nil while loading so callers can distinguish "loading" from "empty".
(rf/reg-sub
 ::media-page-items
 (fn [db _]
   (:media-page-items db)))

;; Total number of items matching the current query (library + filter), or nil
;; when the backend doesn't report a total.
(rf/reg-sub
 ::media-total
 (fn [db _]
   (:media-total db)))

(rf/reg-sub
 ::media-loading?
 (fn [db _]
   (:media-loading? db false)))

;; Server's authoritative "is there a next page" flag. nil when unknown (e.g. a
;; pre-pagination response), in which case callers fall back to a heuristic.
(rf/reg-sub
 ::media-has-more
 (fn [db _]
   (:media-has-more db)))

;; Total page count from the server-reported total. nil when the total is
;; unknown, so the pager can fall back to a next-page heuristic instead.
(rf/reg-sub
 ::media-total-pages
 :<- [::media-total]
 :<- [::media-page-size]
 (fn [[total page-size] _]
   (when (and total (pos? page-size))
     (max 1 (js/Math.ceil (/ total page-size))))))

;; ---------------------------------------------------------------------------
;; Browse by metadata subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
 ::browse-facet
 (fn [db _]
   (:browse-facet db :tags)))

(rf/reg-sub
 ::browse-selection
 (fn [db _]
   (:browse-selection db)))

(rf/reg-sub
 ::browse-list
 (fn [db [_ facet]]
   (get-in db [:browse-lists facet])))

(rf/reg-sub
 ::browse-filter
 (fn [db _]
   (:browse-filter db "")))

(rf/reg-sub
 ::browse-media
 (fn [db [_ facet value]]
   (get-in db [:browse-media [facet value]])))

(rf/reg-sub
 ::browse-media-page
 (fn [db _]
   (:browse-media-page db 1)))

(rf/reg-sub
 ::media-categories
 (fn [db [_ media-id]]
   (get-in db [:media-categories media-id])))

(rf/reg-sub
 ::browse-dimension
 (fn [db _]
   (:browse-dimension db)))

(rf/reg-sub
 ::dimension-values
 (fn [db [_ dim-name]]
   (get-in db [:dimension-values dim-name])))

;; API documentation browser subscriptions

(rf/reg-sub
 ::api-selected-service
 (fn [db _]
   (:api-selected-service db)))

(rf/reg-sub
 ::api-spec
 (fn [db [_ service-id]]
   (get-in db [:api-specs service-id])))

(rf/reg-sub
 ::api-expanded-ops
 (fn [db _]
   (:api-expanded-ops db #{})))

(rf/reg-sub
 ::api-filter
 (fn [db _]
   (:api-filter db "")))

;; ---------------------------------------------------------------------------
;; Schedule / guide subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
 ::channels
 (fn [db _] (:channels db)))

(rf/reg-sub
 ::channels-loading?
 (fn [db _] (:channels-loading? db false)))

(rf/reg-sub
 ::schedule-window-start
 (fn [db _] (:schedule-window-start db (.getTime (js/Date.)))))

(rf/reg-sub
 ::all-channel-events
 (fn [db _] (:channel-events db {})))

(rf/reg-sub
 ::channel-events-loading?
 (fn [db _] (boolean (seq (:channel-events-loading db)))))

(rf/reg-sub
 ::current-channel-id
 (fn [db _] (:current-channel-id db)))

(rf/reg-sub
 ::current-channel
 :<- [::channels]
 :<- [::current-channel-id]
 (fn [[channels id] _]
   (when (and channels id)
     (first (filter #(= (:id %) id) channels)))))

(rf/reg-sub
 ::current-channel-events
 (fn [db _]
   (let [id (:current-channel-id db)]
     (get-in db [:channel-events id]))))

(rf/reg-sub
 ::channel-events-loading-for?
 (fn [db [_ channel-id]]
   (contains? (:channel-events-loading db #{}) channel-id)))

;; ---------------------------------------------------------------------------
;; Jobs subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
 ::jobs
 (fn [db _]
   (let [by-source (:jobs-by-source db)]
     (when (seq by-source)
       (->> by-source
            (mapcat (fn [[source jobs]] (map #(assoc % :source source) jobs)))
            (sort-by #(or (:created-at %) "") >)
            vec)))))

(rf/reg-sub
 ::jobs-loading?
 (fn [db _] (boolean (seq (:jobs-loading db)))))

(rf/reg-sub
 ::channel-playout-job
 :<- [::jobs]
 (fn [jobs [_ channel-id]]
   (some (fn [{:keys [source status metadata] :as job}]
           (when (and (= :pseudovision source)
                      (contains? #{:running :pending :queued} (keyword status))
                      (= (str (or (:channel-id job) (:channel-id metadata)))
                         (str channel-id)))
             job))
         jobs)))

(rf/reg-sub
 ::action-state
 (fn [db [_ action-key]]
   (get-in db [:action-states action-key] {:status :idle})))

(rf/reg-sub
 ::tag-task-options
 (fn [db _]
   (:tag-task-options db {:dry-run true :target-limit nil})))

;; ---------------------------------------------------------------------------
;; Collections subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub
 ::collections
 (fn [db _]
   (vals (:collections db {}))))

(rf/reg-sub
 ::collections-map
 (fn [db _]
   (:collections db {})))

(rf/reg-sub
 ::collection
 (fn [db [_ id]]
   (get-in db [:collections id])))

(rf/reg-sub
 ::current-collection-id
 (fn [db _]
   (:current-collection-id db)))

(rf/reg-sub
 ::collection-items
 (fn [db [_ collection-id]]
   (let [coll      (get-in db [:collections collection-id])
         media-ids (:items coll)]
     (when (seq media-ids)
       (let [items (keep #(get-in db [:media-items %]) media-ids)]
         (when (= (count items) (count media-ids))
           items))))))

(rf/reg-sub
 ::collection-filter
 (fn [db _]
   (:collection-filter db "")))

;; A collection's loaded items, narrowed by the current text filter. nil while
;; items are still loading (mirrors ::collection-items).
(rf/reg-sub
 ::filtered-collection-items
 (fn [[_ collection-id] _]
   [(rf/subscribe [::collection-items collection-id])
    (rf/subscribe [::collection-filter])])
 (fn [[items needle] _]
   (when items
     (if (str/blank? needle)
       items
       (filterv #(item-matches? needle %) items)))))

(rf/reg-sub
 ::new-collection-name
 (fn [db _]
   (:new-collection-name db "")))

(rf/reg-sub
 ::add-to-collection-open?
 (fn [db _]
   (:add-to-collection-open? db false)))
