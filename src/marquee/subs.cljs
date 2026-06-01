(ns marquee.subs
  (:require [re-frame.core :as rf]
            [martian.re-frame :as martian]))

(rf/reg-sub
 ::active-page
 (fn [db _]
   (:active-page db)))

(rf/reg-sub
 ::counter
 (fn [db _]
   (:counter db)))

(rf/reg-sub
 ::api-ready?
 (fn [db _]
   (boolean (martian/instance db))))
