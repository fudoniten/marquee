(ns marquee.pages.media-detail
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.components.card :refer [card card-header card-title
                                             card-description card-content
                                             card-footer]]))

(defn metadata-section [title data]
  (when data
    [:div {:class "mb-4"}
     [:h3 {:class "text-lg font-semibold mb-2"} title]
     [:pre {:class "text-xs bg-muted p-4 rounded-md overflow-x-auto"}
      (js/JSON.stringify (clj->js data) nil 2)]]))

(defn page []
  (let [media-id @(rf/subscribe [::subs/current-media-id])
        media-item @(rf/subscribe [::subs/media-item media-id])
        scheduler-metadata @(rf/subscribe [::subs/scheduler-metadata media-id])]
    [:div {:class "space-y-6"}
     [:div
      [button {:variant :ghost
               :size :sm
               :on-click #(rf/dispatch [::events/navigate :media])}
       "← Back to Media"]]
     
     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"}
       (or (:title media-item) (str "Media Item #" media-id))]
      [:p {:class "text-muted-foreground"}
       "Metadata from Pseudovision and Tunarr Scheduler."]]
     
     ;; Pseudovision data
     [card {}
      [card-header {}
       [card-title {} "Pseudovision Data"]
       [card-description {} "Media information from Pseudovision"]]
      [card-content {}
       (if (nil? media-item)
         [:p {:class "text-muted-foreground"} "Loading..."]
         (if media-item
           [metadata-section nil media-item]
           [:p {:class "text-muted-foreground"} "No data found."]))]]
     
     ;; Tunarr Scheduler metadata
     [card {}
      [card-header {}
       [card-title {} "Tunarr Scheduler Metadata"]
       [card-description {} "Generated and pulled metadata"]]
      [card-content {}
       (if (nil? scheduler-metadata)
         [:p {:class "text-muted-foreground"} "Loading..."]
         (if scheduler-metadata
           [metadata-section nil scheduler-metadata]
           [:p {:class "text-muted-foreground"} "No metadata found."]))]]]))
