(ns marquee.pages.browse
  "Browse the Tunarr Scheduler catalog by tag or dimension."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content
                                             card-footer]]))

(def ^:private page-size 24)

(def ^:private facets
  [{:key :tags       :label "Tags"}
   {:key :dimensions :label "Dimensions"}])

(defn- display-str [v]
  (if (keyword? v) (name v) (str v)))

;;; ── Facet lists ─────────────────────────────────────────────────────────────

(defn facet-tabs [active]
  [:div {:class "flex items-center gap-1 border-b pb-2"}
   (for [{:keys [key label]} facets]
     ^{:key key}
     [button {:variant (if (= key active) :secondary :ghost)
              :size :sm
              :on-click #(rf/dispatch [::events/browse-select-facet key])}
      label])])

(defn filter-input [value]
  [:input {:type "text"
           :class "flex h-10 w-full max-w-sm rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
           :placeholder "Filter…"
           :value value
           :on-change #(rf/dispatch [::events/set-browse-filter (.. % -target -value)])}])

(defn- matches-filter? [filter-text & strings]
  (or (str/blank? filter-text)
      (let [needle (str/lower-case filter-text)]
        (some #(and % (str/includes? (str/lower-case (str %)) needle))
              strings))))

(defn tag-card [{:keys [tag usage-count example-titles]}]
  [card {:class "cursor-pointer transition-colors hover:border-primary/50"
         :on-click #(rf/dispatch [::events/browse-select-item :tags tag])}
   [card-header {:class "p-4"}
    [card-title {:class "text-base flex items-center justify-between gap-2"}
     [:span {:class "truncate"} tag]
     (when usage-count
       [:span {:class "shrink-0 inline-flex items-center rounded-full bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground"}
        usage-count])]
    (when (seq example-titles)
      [card-description {:class "line-clamp-2"}
       (str/join " · " (take 3 example-titles))])]])

(defn tags-list [tags filter-text]
  (let [visible (filterv #(matches-filter? filter-text (:tag %)) tags)]
    (cond
      (nil? tags)      [:p {:class "text-muted-foreground"} "Loading tags…"]
      (empty? tags)    [:p {:class "text-muted-foreground"} "No tags found."]
      (empty? visible) [:p {:class "text-muted-foreground"} "No tags match the filter."]
      :else
      [:div {:class "grid gap-3 sm:grid-cols-2"}
       (for [t visible]
         ^{:key (:tag t)}
         [tag-card t])])))

(defn dimension-card [{:keys [name value-count]}]
  [card {:class "cursor-pointer transition-colors hover:border-primary/50"
         :on-click #(rf/dispatch [::events/browse-select-item :dimensions name])}
   [card-header {:class "p-4"}
    [card-title {:class "text-base flex items-center justify-between gap-2"}
     [:span {:class "truncate"} name]
     (when value-count
       [:span {:class "shrink-0 inline-flex items-center rounded-full bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground"}
        value-count])]]])

(defn dimensions-list [dimensions filter-text]
  (let [visible (filterv #(matches-filter? filter-text (:name %)) dimensions)]
    (cond
      (nil? dimensions)  [:p {:class "text-muted-foreground"} "Loading dimensions…"]
      (empty? dimensions) [:p {:class "text-muted-foreground"} "No dimensions found."]
      (empty? visible)    [:p {:class "text-muted-foreground"} "No dimensions match the filter."]
      :else
      [:div {:class "grid gap-3 sm:grid-cols-2"}
       (for [d visible]
         ^{:key (:name d)}
         [dimension-card d])])))

;;; ── Media results ───────────────────────────────────────────────────────────

(defn- metadata-chips
  "Clickable chips that jump to browsing by that tag or dimension."
  [facet values current]
  (when (seq values)
    [:div {:class "flex flex-wrap gap-1.5"}
     (for [v values
           :let [label (display-str v)]]
       ^{:key label}
       [:button {:class (str "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors "
                             (if (= label current)
                               "bg-primary text-primary-foreground"
                               "bg-secondary text-secondary-foreground hover:bg-secondary/70"))
                 :on-click #(rf/dispatch [::events/browse-select-item facet label])}
        label])]))

(defn media-result-card
  "Card for a media item as returned by the scheduler's browse endpoints."
  [{:keys [id name overview item-kind type production-year tags]} facet selection]
  [card {}
   [card-header {:class "pb-2"}
    [card-title {:class "text-lg"} (or name (str "Item #" id))]
    [card-description {}
     (str/join " · " (remove nil? [(some-> (or item-kind type) display-str)
                                   production-year]))]]
   [card-content {:class "space-y-2"}
    (when overview
      [:p {:class "text-sm text-muted-foreground line-clamp-3"} overview])
    [metadata-chips :tags tags (when (= facet :tags) selection)]]
   (when id
     [card-footer {}
      [button {:size :sm
               :variant :outline
               :on-click #(rf/dispatch [::events/navigate-to-media-detail id])}
       "View Details"]])])

(defn pagination-controls [current-page total-pages]
  (let [has-prev (> current-page 1)
        has-next (< current-page total-pages)]
    [:div {:class "flex items-center justify-between border-t pt-4 mt-6"}
     [:div {:class "flex items-center gap-2"}
      [button {:size :sm
               :variant :outline
               :disabled (not has-prev)
               :on-click #(rf/dispatch [::events/set-browse-media-page (dec current-page)])}
       "← Previous"]
      [button {:size :sm
               :variant :outline
               :disabled (not has-next)
               :on-click #(rf/dispatch [::events/set-browse-media-page (inc current-page)])}
       "Next →"]]
     [:div {:class "text-sm text-muted-foreground"}
      (str "Page " current-page " of " total-pages)]]))

(defn media-results [facet selection]
  (let [items @(rf/subscribe [::subs/browse-media facet selection])
        page  @(rf/subscribe [::subs/browse-media-page])
        total-pages (when items (js/Math.ceil (/ (count items) page-size)))
        start (* (dec page) page-size)
        visible (when (seq items)
                  (subvec (vec items) start (min (+ start page-size) (count items))))]
    [:div {:class "space-y-4"}
     [:div {:class "flex items-center gap-3"}
      [button {:variant :ghost
               :size :sm
               :on-click #(rf/dispatch [::events/browse-clear-selection])}
       (str "← All " (str/lower-case (some :label (filter #(= (:key %) facet) facets))))]]
      (when (= facet :dimensions)
        [:span {:class "text-sm text-muted-foreground"}
         "Dimension tag: " selection])]
     [:div
      [:h2 {:class "text-2xl font-semibold"} selection]
      (when items
        [:p {:class "text-sm text-muted-foreground"}
         (str (count items) " item" (when (not= 1 (count items)) "s"))])]
     (cond
       (nil? items)   [:p {:class "text-muted-foreground"} "Loading media…"]
       (empty? items) [:p {:class "text-muted-foreground"} "No media found."]
       :else
       [:div
        [:div {:class "grid gap-4"}
         (for [item visible]
           ^{:key (or (:id item) (:name item))}
           [media-result-card item facet selection])]
        (when (> total-pages 1)
          [pagination-controls page total-pages])])))

;;; ── Page ────────────────────────────────────────────────────────────────────

(defn page []
  (let [facet       @(rf/subscribe [::subs/browse-facet])
        selection   @(rf/subscribe [::subs/browse-selection])
        filter-text @(rf/subscribe [::subs/browse-filter])
        entries     @(rf/subscribe [::subs/browse-list facet])]
    [:div {:class "space-y-6"}
     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"} "Browse"]
      [:p {:class "text-muted-foreground"}
       "Explore the catalog by tag or dimension."]]

     [facet-tabs facet]

     (if selection
       [media-results facet selection]
       [:div {:class "space-y-4"}
        [filter-input filter-text]
         (case facet
           :tags       [tags-list entries filter-text]
           :dimensions [dimensions-list entries filter-text])])]))
