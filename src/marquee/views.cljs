(ns marquee.views
  (:require [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.pages.home :as home]
            [marquee.pages.media :as media]
            [marquee.pages.media-detail :as media-detail]
            [marquee.pages.browse :as browse]
            [marquee.pages.api-docs :as api-docs]
            [marquee.pages.schedule :as schedule]
            [marquee.pages.jobs :as jobs]
            [marquee.pages.collections :as collections]))

(def pages
  {:home             {:label "Home"     :view home/page          :show-in-nav true}
   :media            {:label "Media"    :view media/page         :show-in-nav true}
   :browse           {:label "Browse"   :view browse/page        :show-in-nav true}
   :api-docs         {:label "API Docs" :view api-docs/page      :show-in-nav true}
   :schedule-grid    {:label "Guide"    :view schedule/grid-page :show-in-nav true}
   :collections      {:label "Collections" :view collections/page :show-in-nav true}
   :jobs             {:label "Jobs"     :view jobs/page          :show-in-nav true}
   :channel-schedule {:label "Schedule" :view schedule/channel-page :show-in-nav false}
   :media-detail     {:label "Media Detail" :view media-detail/page :show-in-nav false}
   :collection-detail {:label "Collection" :view collections/page :show-in-nav false}})

(defn navbar []
  (let [active @(rf/subscribe [::subs/active-page])]
    [:nav {:class "flex flex-wrap items-center gap-1 border-b border-border pb-4"}
     [:span {:class "mr-4 font-semibold text-primary tracking-tight"} "Marquee"]
     (for [[page {:keys [label show-in-nav]}] pages
           :when show-in-nav]
       ^{:key page}
       [button {:variant (if (or (= page active)
                              (and (= page :collections) (= active :collection-detail)))
                          :secondary :ghost)
                :size :sm
                :on-click #(rf/dispatch [::events/navigate page])}
        label])]))

(defn loading []
  [:div {:class "flex items-center justify-center h-screen text-muted-foreground"}
   "Loading…"])

(defn app []
  (let [active  @(rf/subscribe [::subs/active-page])
        ready?  @(rf/subscribe [::subs/api-ready?])
        view    (get-in pages [active :view])]
    (if-not ready?
      [loading]
      ;; A single, consistent wide container for every tab so nothing jumps
      ;; around when switching pages. Responsive padding keeps it usable on
      ;; phones while filling a desktop window.
      [:div {:class "mx-auto w-full max-w-6xl px-4 sm:px-6 lg:px-8 py-8"}
       [navbar]
       [:main {:class "py-8"}
        [view]]])))
