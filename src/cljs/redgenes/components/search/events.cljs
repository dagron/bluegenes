(ns redgenes.components.search.events
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [com.rpl.specter :refer [traverse]])
  (:require [re-frame.core :as re-frame :refer [reg-event-db reg-event-fx reg-fx dispatch subscribe]]
            [redgenes.db :as db]
            ))


(def max-results 99);;todo - this is only used in a cond right now, won't modify number of results returned. IMJS was being tricky;

(reg-event-db
  :search/set-search-term
  (fn [db [_ search-term]]
    (assoc db :search-term search-term)))

(defn circular-index-finder
  "Returns the index of a result item, such that going down at the bottom loops to the top and vice versa. Element -1 is 'show all'"
  [direction]
  (let [result-index (subscribe [:quicksearch-selected-index])
        results-count (count @(subscribe [:suggestion-results]))
        next-number (if (= direction :next)
                      (inc @result-index)
                      (dec @result-index))
        looped-number (cond
                        (>= next-number results-count)
                        ;;if we go past the end, loop to the start
                          -1
                        (< next-number -1)
                        ;;if we go before the start, loop to the end.
                          (- results-count 1)
                        ;;if we fall through this far, next-number was in fact correct.
                        :else next-number
                      )]
  looped-number))

(reg-event-db
  :search/move-selection
  (fn [db [_ direction-to-move]]
      (assoc db :quicksearch-selected-index (circular-index-finder direction-to-move))
))

(reg-event-db
  :search/reset-selection
  (fn [db [_ direction-to-move]]
      (assoc db :quicksearch-selected-index -1)
))


(defn sort-by-value [result-map]
 "Sort map results by their values. Used to order the category maps correctly"
 (into (sorted-map-by (fn [key1 key2]
                        (compare [(get result-map key2) key2]
                                 [(get result-map key1) key1])))
       result-map))

(reg-event-db
 :search/save-results
 (fn [db [_ results]]
   (if (some? (:active-filter (:search-results db)))
     ;;if we're returning a filter result, leave the old facets intact.
     (assoc-in db [:search-results :results] (.-results results))
     ;;if we're returning a non-filtered result, add new facets to the atom
     (assoc db :search-results
       {
       :results  (.-results results)
       :highlight-results (:highlight-results (:search-results db))
       :facets {
         :organisms (sort-by-value (js->clj (aget results "facets" "organism.shortName")))
         :category (sort-by-value (js->clj (aget results "facets" "Category")))}}))
))

(defn search
 "search for the given term via IMJS promise. Filter is optional"
 [& filter]
   (let [searchterm @(re-frame/subscribe [:search-term])
         mine (js/imjs.Service. (clj->js {:root @(subscribe [:mine-url])}))
         search {:q searchterm :Category filter}
         id-promise (-> mine (.search (clj->js search)))]
     (-> id-promise (.then
         (fn [results]
           (dispatch [:search/save-results results]))))))

(reg-event-fx
  :search/full-search
  (fn [{db :db}]
    (let [filter (:active-filter (:search-results db))]
    (search filter))
{:db db}))

(reg-event-db :search/reset-quicksearch
  (fn [db]
    (assoc db :suggestion-results nil)
))

; (reg-event-db
;   :search/set-active-filter
;   (fn [db [_ filter]]
;     (assoc-in db [:search-results :active-filter] filter)
; ))

(defn is-active-result? [result active-filter]
 "returns true is the result should be considered 'active' - e.g. if there is no filter at all, or if the result matches the active filter type."
   (or
     (= active-filter (.-type result))
     (nil? active-filter)))

(defn count-current-results [results filter]
 "returns number of results currently shown, taking into account result limits nd filters"
 (count
   (remove
     (fn [result]
       (not (is-active-result? result filter))) results)))

(reg-fx
 :load-more-results-if-needed
 ;;output the results we have client side alredy (ie if a non-filtered search returns 100 results due to a limit, but indicates that there are 132 proteins in total, we'll show all the proteins we have when we filter down to just proteins, so the user might not even notice that we're fetching the rest in the background.)
 ;;while the remote results are loading. Good for slow connections.
 (fn [search-results]
   (let [results (:results search-results)
         filter (:active-filter search-results)
         filtered-result-count (get (:category (:facets search-results)) filter)
         more-filtered-results-to-show? (< (count-current-results results filter) filtered-result-count)
         more-results-than-max? (<= (count-current-results results filter) max-results)]
     (cond (and  more-filtered-results-to-show? more-results-than-max?)
       (dispatch [:search/full-search]))
)))


(reg-event-fx
  :search/set-active-filter
  (fn [{:keys [db]} [_ filter]]
    (let [new-db (assoc-in db [:search-results :active-filter] filter)]
    {:db new-db
     :load-more-results-if-needed (:search-results new-db)}
)))

(reg-event-fx
  :search/remove-active-filter
  (fn [{:keys [db]}]
    {:db (assoc db :search-results (dissoc (:search-results db) :active-filter))
     :dispatch [:search/full-search]
     }
))

(reg-event-db :search/highlight-results
  (fn [db [_ highlight?]]
    (assoc-in db [:search-results :highlight-results] highlight?)
))
