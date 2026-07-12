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

(defn- tag-chip [t]
  [:span {:class "inline-flex items-center rounded-full bg-secondary px-2.5 py-0.5 text-xs font-medium text-secondary-foreground"}
   t])

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

   (when (seq tags)
     [card {}
      [card-header {:class "pb-2"} [card-title {:class "text-base"} "Tags"]]
      [card-content {}
       [:div {:class "flex flex-wrap gap-1.5"}
        (for [t (sort tags)]
          ^{:key t} [tag-chip t])]]])])

(defn page []
  (let [entry @(rf/subscribe [::subs/grout-item])]
    [:div {:class "space-y-6"}
     [button {:variant :ghost :size :sm
              :on-click #(rf/dispatch [::events/set-media-source :grout])}
      "← Back to Grout"]
     (case (:status entry)
       :loading [:p {:class "text-muted-foreground"} "Loading item…"]
       :error   [:p {:class "text-destructive"} "Failed to load this item."]
       :loaded  [detail (:item entry)]
       [:p {:class "text-muted-foreground"} "Loading item…"])]))
