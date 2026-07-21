(ns marquee.pages.grout
  "Grout source view for the Media tab.

  Grout stores filler and long-form non-IMDB media (bumpers, interstitials,
  debates, video essays, YouTube content). Its tags are namespaced by
  convention (`ns:value`) and fall into distinct roles:

    - `parent-directory:<slug>` — a collection (creator/series/archive). This
      is the primary browse axis, enumerated via GET /grout/directory-profiles.
    - `channel:<x>` / the `channel` column — the owning PV channel.
    - `content-type:<kind>` / `filename:<name>` — structural/identity noise,
      hidden from the descriptive chip row.
    - everything else — descriptive free-form + `audience:` dimension tags.

  The view is two levels, mirroring the Browse page: a Collections index, then a
  drill-down into one collection's media (a `GET /grout/media?tags=<pd>` query)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.action-button :refer [action-btn]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content
                                             card-footer]]))

(def ^:private page-size 24)

;; Structural/identity namespaces suppressed from the descriptive chip row.
(def ^:private hidden-tag-namespaces #{"filename" "content-type" "parent-directory"})

;;; ── helpers ──────────────────────────────────────────────────────────────

(defn parse-tag
  "Split `ns:value` into [namespace value]; [nil tag] when there's no namespace."
  [t]
  (if-let [idx (str/index-of t ":")]
    [(subs t 0 idx) (subs t (inc idx))]
    [nil t]))

(defn- humanize
  "Turn a `parent-directory` slug into a readable label: hyphens → spaces,
   title-cased. Used as a fallback when a collection has no concept name."
  [slug]
  (->> (str/split (str slug) #"-")
       (remove str/blank?)
       (map str/capitalize)
       (str/join " ")))

(defn- long-form? [kind] (= kind "program"))

(defn- filename-tag-name
  "The original filename (extension stripped) from an item's `filename:` tag, or
   nil. grout-cli always adds it, so it's a far better display name than the
   bare UUID — and Grout deliberately keeps AI out of naming, so this is the
   right source rather than a generated title."
  [tags]
  (some (fn [t]
          (let [[ns v] (parse-tag t)]
            (when (and (= ns "filename") (not (str/blank? v)))
              (str/replace v #"\.[^.]+$" ""))))
        tags))

(defn display-name
  "Best available human name for a media item: an explicit name, else the
   original filename, else a kind-appropriate placeholder, else the id."
  [{:keys [name tags kind id]}]
  (or (not-empty name)
      (filename-tag-name tags)
      (when (long-form? kind) "Untitled")
      (str "Item #" id)))

(defn format-duration
  "Milliseconds → `M:SS` (or `H:MM:SS` past an hour)."
  [ms]
  (when (and ms (pos? ms))
    (let [total (js/Math.round (/ ms 1000))
          h     (quot total 3600)
          m     (quot (mod total 3600) 60)
          s     (mod total 60)
          pad   #(if (< % 10) (str "0" %) (str %))]
      (if (pos? h)
        (str h ":" (pad m) ":" (pad s))
        (str m ":" (pad s))))))

(defn- status-pill [status]
  (let [cls (case status
              "ready"   "bg-green-100 text-green-800"
              "pending" "bg-amber-100 text-amber-800"
              "failed"  "bg-red-100 text-red-800"
              "bg-gray-100 text-gray-800")]
    [:span {:class (str "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium " cls)}
     (or status "unknown")]))

(defn- chip
  ([label] (chip label nil))
  ([label variant]
   [:span {:class (str "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium "
                       (case variant
                         :channel "bg-primary/10 text-primary"
                         "bg-secondary text-secondary-foreground"))}
    label]))

(defn- descriptive-chips
  "Render a media item's tags minus the structural/identity namespaces. Channel
   tags are styled distinctly; everything else is a muted chip."
  [tags channel]
  (let [visible (remove (fn [t] (contains? hidden-tag-namespaces (first (parse-tag t)))) tags)]
    (when (or (seq visible) (not (str/blank? channel)))
      [:div {:class "flex flex-wrap gap-1.5"}
       (when-not (str/blank? channel)
         ^{:key (str "chan:" channel)} [chip (str "channel: " channel) :channel])
       (for [t visible
             :let [[ns v] (parse-tag t)]]
         ^{:key t}
         (if (= ns "channel")
           [chip (str "channel: " v) :channel]
           [chip t]))])))

;;; ── collections index ──────────────────────────────────────────────────────

(defn- collection-card
  [{:keys [tag concept-name item-count status dimensions]}]
  (let [channel (first (:channel dimensions))
        label   (if (str/blank? concept-name)
                  (humanize (second (parse-tag tag)))
                  concept-name)]
    [card {:class "cursor-pointer transition-colors hover:border-primary/50"
           :on-click #(rf/dispatch [::events/open-grout-collection tag])}
     [card-header {:class "p-4 space-y-2"}
      [card-title {:class "text-base flex items-center justify-between gap-2"}
       [:span {:class "truncate"} label]
       (when item-count
         [:span {:class "shrink-0 inline-flex items-center rounded-full bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground"}
          item-count])]
      [:div {:class "flex flex-wrap items-center gap-1.5"}
       [status-pill status]
       (when-not (str/blank? channel) [chip (str "channel: " channel) :channel])]]]))

(defn- collections-index [collections filter-text]
  (let [;; Empty collections (no live items) are noise — a profile can outlive
        ;; the media that created it, or exist before its first upload lands.
        non-empty (when (vector? collections)
                    (filterv #(pos? (or (:item-count %) 0)) collections))
        visible (when non-empty
                  (filterv (fn [{:keys [concept-name tag]}]
                             (or (str/blank? filter-text)
                                 (let [needle (str/lower-case filter-text)]
                                   (or (str/includes? (str/lower-case (str concept-name)) needle)
                                       (str/includes? (str/lower-case (str tag)) needle)))))
                           non-empty))]
    (cond
      (nil? collections)   [:p {:class "text-muted-foreground"} "Loading collections…"]
      (= :error collections) [:p {:class "text-destructive"} "Failed to load Grout collections."]
      (empty? non-empty)   [:p {:class "text-muted-foreground"} "No collections yet. Upload media with grout-cli --upload-dir."]
      (empty? visible)     [:p {:class "text-muted-foreground"} "No collections match the filter."]
      :else
      [:div {:class "grid gap-3 sm:grid-cols-2 lg:grid-cols-3"}
       (for [c visible]
         ^{:key (:tag c)}
         [collection-card c])])))

;;; ── media grid (collection drill-down) ──────────────────────────────────────

(defn- media-card
  [{:keys [id description kind channel duration-ms source-url tags] :as item}]
  [card {:class "flex flex-col"}
   [card-header {:class "pb-2"}
    [card-title {:class "text-base flex items-start justify-between gap-2"}
     [:span {:class "break-words"} (display-name item)]
     (when-let [d (format-duration duration-ms)]
       [:span {:class "shrink-0 font-mono text-xs text-muted-foreground"} d])]
    (when kind
      [card-description {} kind])]
   [card-content {:class "flex-1 space-y-2"}
    (when (and description (long-form? kind))
      [:p {:class "text-sm text-muted-foreground line-clamp-3"} description])
    [descriptive-chips tags channel]]
   [card-footer {:class "gap-3"}
    [button {:size :sm :variant :outline
             :on-click #(rf/dispatch [::events/navigate-to-grout-detail id])}
     "View details"]
    (when (and source-url (long-form? kind))
      [:a {:href source-url :target "_blank" :rel "noopener noreferrer"
           :class "text-sm text-primary underline self-center"}
       "Source ↗"])]])

(defn- kind-filter [active]
  [:div {:class "flex items-center gap-1"}
   (for [[k label] [[nil "All"] ["program" "Long-form"] ["filler" "Filler"] ["bumper" "Bumpers"]]]
     ^{:key (or k "all")}
     [button {:variant (if (= k active) :secondary :ghost)
              :size :sm
              :on-click #(rf/dispatch [::events/set-grout-kind k])}
      label])])

(defn- pagination-controls [current-page total-pages]
  [:div {:class "flex items-center justify-between border-t pt-4 mt-6"}
   [:div {:class "flex items-center gap-2"}
    [button {:size :sm :variant :outline
             :disabled (<= current-page 1)
             :on-click #(rf/dispatch [::events/set-grout-media-page (dec current-page)])}
     "← Previous"]
    [button {:size :sm :variant :outline
             :disabled (>= current-page total-pages)
             :on-click #(rf/dispatch [::events/set-grout-media-page (inc current-page)])}
     "Next →"]]
   [:div {:class "text-sm text-muted-foreground"} (str "Page " current-page " of " total-pages)]])

;;; ── manual override (context + dimension values) ────────────────────────────
;; PATCH /grout/directory-profiles/:tag bypasses Tunabrain entirely: an
;; operator can correct a wrong classification (e.g. reassign a directory of
;; retro game-ad commercials from goldenreels to toontown/infobytes/galaxy)
;; and/or hand it grounding notes for its next automatic pass. Saving
;; dimension values locks the profile server-side against a later
;; growth-triggered re-enrichment silently overwriting the correction.

(def ^:private input-class
  "flex h-8 rounded-md border border-input bg-background px-2 py-1 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring")

;; Tunarr Scheduler's fixed dimension set (kept in sync with Tunabrain's
;; `_DIMENSION_KEYS` and Grout's static `:dimension-descriptions` config) —
;; every dimension a profile can carry, shown as a fixed row of inputs rather
;; than an open-ended "add a dimension" affordance.
(def ^:private known-dimension-keys ["channel" "audience" "freshness" "season" "time-slot"])

(defn- dims->text-map
  "A profile's `:dimensions` (keyword-or-string keyed, vector values) as a
   plain {dim-name-string comma-joined-string} map for editable text inputs,
   pre-seeded with every known dimension key (blank when unset)."
  [dimensions]
  (into {}
        (map (fn [k] [k (str/join ", " (get dimensions (keyword k)))]))
        known-dimension-keys))

(defn- text-map->dims
  "Parse the editable {dim-name comma-string} map back into a `{dim-name
   [values]}` map: trims and drops blanks, and omits any dimension left
   entirely empty (clearing a field removes that dimension from the profile,
   matching the PATCH's full-replace semantics for :dimensions)."
  [text-map]
  (into {}
        (keep (fn [[k v]]
                (let [values (->> (str/split (or v "") #",")
                                  (map str/trim)
                                  (remove str/blank?)
                                  vec)]
                  (when (seq values) [k values]))))
        text-map))

(defn- locked-badge []
  [:span {:class "inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"}
   "Locked"])

(defn- context-editor
  "Context text/links, saved independently of dimension values (see
   manual-override-editor) so adding a grounding note never has the
   side-effect of re-locking the collection — the PATCH only locks when
   :dimensions/:tags are actually present in the body, and this section never
   sends those."
  [tag context]
  (let [ctx-text  (r/atom (or (:text context) ""))
        ctx-links (r/atom (str/join ", " (:links context)))]
    (fn [tag _context]
      (let [save-key [:grout-context-save tag]]
        [:div {:class "space-y-1.5"}
         [:label {:class "text-xs font-medium text-muted-foreground"} "Context for Tunabrain"]
         [:textarea {:class (str input-class " h-20 w-full resize-y")
                     :placeholder "Notes to ground the next classification, e.g. \"these are retro VIDEO GAME ads, not vintage film content\""
                     :value @ctx-text
                     :on-change #(reset! ctx-text (.. % -target -value))}]
         [:input {:type "text"
                  :class (str input-class " w-full")
                  :placeholder "Reference links, comma-separated (optional; echoed as text, not fetched)"
                  :value @ctx-links
                  :on-change #(reset! ctx-links (.. % -target -value))}]
         [:div {:class "flex items-center gap-2 pt-1"}
          [action-btn {:action-key save-key
                       :label      "Save context"
                       :on-click   #(rf/dispatch
                                     [::events/patch-grout-collection tag
                                      {:context {:text  (str/trim @ctx-text)
                                                 :links (->> (str/split @ctx-links #",")
                                                            (map str/trim)
                                                            (remove str/blank?)
                                                            vec)}}
                                      save-key])}]
          [:span {:class "text-xs text-muted-foreground"}
           "Used on the next classification pass; doesn't change existing dimensions/tags."]]]))))

(defn- dimension-override-editor
  "Manual dimension values, saved independently of context (see
   context-editor above). Saving locks the profile server-side against a
   later growth-triggered re-enrichment overwriting the correction; Unlock
   clears that without touching the saved values."
  [tag dimensions locked]
  (let [dim-text (r/atom (dims->text-map dimensions))]
    (fn [tag _dimensions locked]
      (let [save-key   [:grout-dims-save tag]
            unlock-key [:grout-override-unlock tag]]
        [:div {:class "space-y-1.5"}
         [:label {:class "text-xs font-medium text-muted-foreground"} "Dimension values (comma-separated)"]
         [:div {:class "grid gap-2 sm:grid-cols-2"}
          (for [k known-dimension-keys]
            ^{:key k}
            [:label {:class "flex flex-col gap-1 text-xs"}
             [:span {:class "text-muted-foreground"} k]
             [:input {:type "text"
                      :class input-class
                      :placeholder (when (= k "channel") "e.g. toontown, infobytes, galaxy")
                      :value (get @dim-text k "")
                      :on-change #(swap! dim-text assoc k (.. % -target -value))}]])]
         [:div {:class "flex flex-wrap items-center gap-2 pt-1"}
          [action-btn {:action-key save-key
                       :label      "Save dimensions"
                       :on-click   #(rf/dispatch
                                     [::events/patch-grout-collection tag
                                      {:dimensions (text-map->dims @dim-text)}
                                      save-key])}]
          (if locked
            [action-btn {:action-key unlock-key
                         :label      "Unlock"
                         :variant    :ghost
                         :on-click   #(rf/dispatch
                                       [::events/patch-grout-collection tag {:locked false} unlock-key])}]
            [:span {:class "text-xs text-muted-foreground"}
             "Saving locks this collection against automatic re-enrichment."])]]))))

(defn- manual-override-editor
  "Editable panel for a collection's Tunabrain grounding context and
   dimension values — two independent sections so editing one never has a
   side effect on the other (see context-editor / dimension-override-editor)."
  [tag context dimensions locked]
  [:div {:class "space-y-4"}
   [context-editor tag context]
   [dimension-override-editor tag dimensions locked]])

(defn- manual-override-card
  "Collapsed by default: most collections never need a manual correction, and
   showing the editor expanded on every visit pushes the (usually far more
   relevant) media grid down the page for no reason. Click the header to
   expand/collapse — local, ephemeral UI state, not persisted across
   navigation or collections."
  [collection]
  (let [open? (r/atom false)]
    (fn [collection]
      [card {}
       [card-header {:class "pb-2 cursor-pointer select-none hover:bg-accent/30 transition-colors rounded-t-lg"
                     :on-click #(swap! open? not)}
        [card-title {:class "text-base flex items-center justify-between gap-2"}
         [:span "Manual override"]
         [:span {:class "text-muted-foreground text-sm font-normal"} (if @open? "▾" "▸")]]
        [card-description {}
         "Correct a wrong classification directly, or give Tunabrain extra grounding notes for its next automatic pass."]]
       (when @open?
         [card-content {}
          [manual-override-editor (:tag collection) (:context collection)
           (:dimensions collection) (:locked collection)]])])))

(defn- collection-view [collection]
  (let [entry       @(rf/subscribe [::subs/grout-media])
        page        @(rf/subscribe [::subs/grout-media-page])
        kind        @(rf/subscribe [::subs/grout-kind])
        filter-text @(rf/subscribe [::subs/grout-filter])
        label       (if (str/blank? (:concept-name collection))
                      (humanize (second (parse-tag (:tag collection))))
                      (:concept-name collection))
        items       (:items entry)
        filtered    (if (str/blank? filter-text)
                      items
                      (let [needle (str/lower-case filter-text)]
                        (filterv (fn [{:keys [name description tags]}]
                                   (some #(and % (str/includes? (str/lower-case (str %)) needle))
                                         (concat [name description] tags)))
                                 items)))
        total-pages (max 1 (js/Math.ceil (/ (count filtered) page-size)))
        n           (count filtered)
        start       (min (* (dec page) page-size) n)
        visible     (when (seq filtered)
                      (subvec (vec filtered) start (min (+ start page-size) n)))]
    [:div {:class "space-y-4"}
     [:div {:class "flex items-center gap-3"}
      [button {:variant :ghost :size :sm
               :on-click #(rf/dispatch [::events/close-grout-collection])}
       "← All collections"]]
     [:div
      [:h2 {:class "text-2xl font-semibold flex items-center gap-2"}
       label
       (when (:locked collection) [locked-badge])]
      (when (:status collection)
        [:div {:class "mt-1"} [status-pill (:status collection)]])]
     [card {}
      [card-content {:class "pt-6"}
       [:div {:class "flex flex-wrap items-center gap-2"}
        [:span {:class "text-xs font-medium uppercase tracking-wide text-muted-foreground"}
         "Curation"]
        [action-btn {:action-key [:grout-recategorize (:tag collection)]
                     :label      "Recategorize"
                     :on-click   #(rf/dispatch [::events/recategorize-grout-collection (:tag collection) label])}]
        [:span {:class "text-xs text-muted-foreground"}
         "Re-derives this directory's channel & tags via Tunabrain and fans them out to every item in it."]]]]
     [manual-override-card collection]
     [kind-filter kind]
     (when (or (seq items) (not (str/blank? filter-text)))
       [:input {:type "search"
                :placeholder "Filter items…"
                :value filter-text
                :on-change #(rf/dispatch [::events/set-grout-filter (.. % -target -value)])
                :class "flex h-10 w-full max-w-sm rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"}])
     (case (:status entry)
       :loading [:p {:class "text-muted-foreground"} "Loading media…"]
       :error   [:p {:class "text-destructive"} "Failed to load media for this collection."]
       :loaded
       (if (empty? filtered)
         [:p {:class "text-muted-foreground"}
          (if (str/blank? filter-text) "No media in this collection." "No items match the filter.")]
         [:div
          [:p {:class "text-sm text-muted-foreground"}
           (str (count filtered) " item" (when (not= 1 (count filtered)) "s"))]
          [:div {:class "grid gap-4 sm:grid-cols-2 lg:grid-cols-3 mt-2"}
           (for [item visible]
             ^{:key (:id item)}
             [media-card item])]
          (when (> total-pages 1)
            [pagination-controls page total-pages])])
       ;; no entry yet
       [:p {:class "text-muted-foreground"} "Loading media…"])]))

;;; ── entry ───────────────────────────────────────────────────────────────────

(defn view
  "The Grout source body, rendered inside the Media tab."
  []
  (let [collections @(rf/subscribe [::subs/grout-collections])
        selected    @(rf/subscribe [::subs/grout-selected-collection])
        collection  @(rf/subscribe [::subs/grout-collection])
        filter-text @(rf/subscribe [::subs/grout-filter])]
    (if collection
      ;; `selected` is the profile from the index; fall back to a bare tag map
      ;; when the collection was deep-linked before the index loaded.
      [collection-view (or selected {:tag collection})]
      [:div {:class "space-y-4"}
       [:p {:class "text-sm text-muted-foreground"}
        "Filler and long-form media from Grout, grouped into collections."]
       (when (vector? collections)
         [:input {:type "search"
                  :placeholder "Filter collections…"
                  :value filter-text
                  :on-change #(rf/dispatch [::events/set-grout-filter (.. % -target -value)])
                  :class "flex h-10 w-full max-w-sm rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"}])
       [collections-index collections filter-text]])))
