(ns marquee.pages.grout-detail
  "Detail view for a single Grout media item (GET /grout/media/:id).

  Unlike the library detail page (Pseudovision + Jellyfin), this is the only
  UI where the user can *delete* Grout media, so it carries a confirm-guarded
  delete that soft-deletes (supersedes) the item — reversible, and it drops
  the item from every listing."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.pages.grout :as grout]
            [marquee.components.button :refer [button]]
            [marquee.components.action-button :refer [action-btn]]
            [marquee.components.card :refer [card card-header card-title card-content]]))

(defn- format-timestamp [v]
  (if (and (string? v) (re-matches #"\d{4}-\d{2}-\d{2}T.*" v))
    (let [d (js/Date. v)]
      (if (js/isNaN (.getTime d)) v (.toLocaleString d)))
    (str v)))

(defn- field [label value]
  (when (and value (not (and (string? value) (str/blank? value))))
    [:div {:class "flex gap-3 py-1.5 border-b border-border last:border-0"}
     [:span {:class "w-32 shrink-0 text-sm text-muted-foreground"} label]
     [:span {:class "text-sm break-words"} value]]))

(def ^:private input-class
  "flex h-8 rounded-md border border-input bg-background px-2 py-1 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring")

(defn- tag-editor
  "Editable tags for a Grout item. Grout owns its tags directly (no Jellyfin /
   Scheduler indirection), so add/remove PATCH straight through to Grout. Tags
   are shown sorted; each carries an × to remove it. Structural namespaces
   (parent-directory:, filename:, content-type:) are shown too, since this is the
   one surface where an operator corrects them."
  [id]
  (let [new-tag (r/atom "")]
    (fn [id tags]
      (let [add! (fn []
                   (let [t (str/trim @new-tag)]
                     (when (seq t)
                       (rf/dispatch [::events/add-grout-tag id t])
                       (reset! new-tag ""))))]
        [:div {:class "space-y-2"}
         (if (seq tags)
           [:div {:class "flex flex-wrap gap-1.5"}
            (for [t (sort tags)]
              ^{:key t}
              [:span {:class "inline-flex items-center gap-1 rounded-full bg-secondary pl-2.5 pr-1 py-0.5 text-xs font-medium text-secondary-foreground"}
               t
               [:button {:class    "inline-flex items-center justify-center w-4 h-4 rounded-full text-[10px] text-secondary-foreground/60 hover:text-destructive hover:bg-destructive/10 transition-colors ml-0.5"
                         :title    "Remove"
                         :on-click #(rf/dispatch [::events/remove-grout-tag id t])}
                "×"]])]
           [:p {:class "text-xs text-muted-foreground"} "No tags set."])
         [:div {:class "flex gap-2"}
          [:input {:type        "text"
                   :class       input-class
                   :placeholder "Add tag…"
                   :value       @new-tag
                   :on-change   #(reset! new-tag (.. % -target -value))
                   :on-key-down #(when (= "Enter" (.-key %))
                                   (.preventDefault %)
                                   (add!))}]
          [button {:size     :sm
                   :variant  :outline
                   :disabled (str/blank? @new-tag)
                   :on-click add!}
           "Add"]]]))))

(defn- delete-controls [id]
  (let [confirming? (r/atom false)]
    (fn [id]
      (if @confirming?
        [:div {:class "flex items-center gap-2"}
         [:span {:class "text-sm text-muted-foreground"} "Delete this item?"]
         [button {:size :sm :variant :destructive
                  :on-click #(rf/dispatch [::events/delete-grout-item id])}
          "Delete"]
         [button {:size :sm :variant :ghost
                  :on-click #(reset! confirming? false)}
          "Cancel"]]
        [button {:size :sm :variant :outline
                 :on-click #(reset! confirming? true)}
         "Delete"]))))

(defn- curation-card
  "Request AI (Tunabrain) enrichment for this item — Grout's equivalent of the
   library page's Retag/Recategorize. Re-runs metadata derivation and refreshes
   the item's tags/description in place on success."
  [id enriched]
  [card {}
   [card-content {:class "pt-6"}
    [:div {:class "flex flex-wrap items-center gap-2"}
     [:span {:class "text-xs font-medium uppercase tracking-wide text-muted-foreground"}
      "Curation"]
     [action-btn {:action-key [:grout-enrich id]
                  :label      (if enriched "Re-enrich" "Enrich")
                  :on-click   #(rf/dispatch [::events/enrich-grout-item id])}]
     [:span {:class "text-xs text-muted-foreground"}
      (if enriched "Already enriched — re-run to refresh tags & description."
          "Not yet enriched — derive tags & description via Tunabrain.")]]]])

(defn- detail [{:keys [id kind channel duration-ms description source source-url
                       enriched created-at width height vcodec acodec tags] :as item}]
  [:div {:class "space-y-6"}
   [:div {:class "flex items-start justify-between gap-4"}
    [:div
     [:h1 {:class "text-2xl font-bold tracking-tight break-words"} (grout/display-name item)]
     (when kind [:p {:class "text-muted-foreground"} kind])]
    [delete-controls id]]

   [card {}
    [card-header {:class "pb-2"} [card-title {:class "text-base"} "Details"]]
    [card-content {}
     [field "Duration" (grout/format-duration duration-ms)]
     [field "Kind" kind]
     [field "Channel" (or channel "generic (any channel)")]
     [field "Description" description]
     [field "Source" source]
     [field "Source URL" (when source-url
                           [:a {:href source-url :target "_blank" :rel "noopener noreferrer"
                                :class "text-primary underline"} source-url])]
     [field "Resolution" (when (and width height) (str width "×" height))]
     [field "Video codec" vcodec]
     [field "Audio codec" acodec]
     [field "Enriched" (if enriched "yes" "no")]
     [field "Added" (when created-at (format-timestamp created-at))]
     [field "ID" [:span {:class "font-mono text-xs"} (str id)]]]]

   [card {}
    [card-header {:class "pb-2"} [card-title {:class "text-base"} "Tags"]]
    [card-content {}
     [tag-editor id tags]]]

   [curation-card id enriched]])

(defn page []
  (let [entry @(rf/subscribe [::subs/grout-item])]
    [:div {:class "space-y-6"}
     [button {:variant :ghost :size :sm
              :on-click #(rf/dispatch [::events/navigate-back [::events/set-media-source :grout]])}
      "← Back"]
     (case (:status entry)
       :loading [:p {:class "text-muted-foreground"} "Loading item…"]
       :error   [:p {:class "text-destructive"} "Failed to load this item."]
       :loaded  [detail (:item entry)]
       [:p {:class "text-muted-foreground"} "Loading item…"])]))
