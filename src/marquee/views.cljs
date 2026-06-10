(ns marquee.views
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.pages.home :as home]
            [marquee.pages.about :as about]
            [marquee.pages.media :as media]
            [marquee.pages.media-detail :as media-detail]
            [marquee.pages.api-docs :as api-docs]
            [marquee.pages.schedule :as schedule]
            [marquee.pages.jobs :as jobs]))

(def pages
  {:home             {:label "Home"     :view home/page          :show-in-nav true}
   :about            {:label "About"    :view about/page         :show-in-nav true}
   :media            {:label "Media"    :view media/page         :show-in-nav true}
   :api-docs         {:label "API Docs" :view api-docs/page      :show-in-nav true}
   :schedule-grid    {:label "Guide"    :view schedule/grid-page :show-in-nav true}
   :jobs             {:label "Jobs"     :view jobs/page          :show-in-nav true}
   :channel-schedule {:label "Schedule" :view schedule/channel-page :show-in-nav false}
   :media-detail     {:label "Media Detail" :view media-detail/page :show-in-nav false}})

(defn navbar []
  (let [active @(rf/subscribe [::subs/active-page])]
    [:nav {:class "flex items-center gap-1 border-b pb-4"}
     [:span {:class "mr-4 font-semibold"} "Marquee"]
     (for [[page {:keys [label show-in-nav]}] pages
           :when show-in-nav]
       ^{:key page}
       [button {:variant (if (= page active) :secondary :ghost)
                :size :sm
                :on-click #(rf/dispatch [::events/navigate page])}
        label])]))

(defn loading []
  [:div {:class "flex items-center justify-center h-screen text-muted-foreground"}
   "Loading…"])

(defn app []
  (let [active  @(rf/subscribe [::subs/active-page])
        ready?  @(rf/subscribe [::subs/api-ready?])
        view    (get-in pages [active :view])
        ;; Guide and API docs benefit from more horizontal room.
        width   (if (#{:api-docs :schedule-grid} active) "max-w-5xl" "max-w-2xl")]
    (if-not ready?
      [loading]
      [:div {:class (str "container mx-auto py-10 " width)}
       [navbar]
       [:main {:class "py-8"}
        [view]]])))
