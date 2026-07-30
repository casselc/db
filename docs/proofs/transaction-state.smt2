(declare-datatypes
  ()
  ((Impl impl_old impl_prior_guard impl_fixed)))
(declare-datatypes
  ()
  ((Physical physical_idle physical_open physical_unknown)))
(declare-datatypes
  ()
  ((Scenario
     sc_outer_success
     sc_begin_failure
     sc_outer_body_rollback_success
     sc_outer_body_rollback_failure
     sc_outer_commit_recovery_success
     sc_outer_commit_recovery_failure
     sc_outer_rollback_only_success
     sc_outer_rollback_only_failure
     sc_nested_body_recovered
     sc_caught_nested_cleanup_outer_rollback_success
     sc_caught_nested_cleanup_outer_rollback_failure
     sc_caught_nested_completion_recovered
     sc_caught_nested_completion_cleanup_outer_rollback_success
     sc_caught_nested_completion_cleanup_outer_rollback_failure)))

(declare-const impl Impl)
(declare-const scenario Scenario)

;; Finite command outcomes supplied by each scenario.
(declare-const outer_begin_ok Bool)
(declare-const outer_body_failed Bool)
(declare-const rollback_only Bool)
(declare-const outer_commit_fails Bool)
(declare-const outer_rollback_fails Bool)
(declare-const nested_present Bool)
(declare-const nested_body_failed Bool)
(declare-const nested_rollback_succeeds Bool)
(declare-const nested_release_after_rollback_fails Bool)
(declare-const nested_completion_fails Bool)
(declare-const nested_completion_recovery_fails Bool)
(declare-const nested_error_caught Bool)

(assert (! (= outer_begin_ok (not (= scenario sc_begin_failure)))
           :named outer_begin_ok_definition))
(assert (! (= outer_body_failed
              (or (= scenario sc_outer_body_rollback_success)
                  (= scenario sc_outer_body_rollback_failure)))
           :named outer_body_failed_definition))
(assert (! (= rollback_only
              (or (= scenario sc_outer_rollback_only_success)
                  (= scenario sc_outer_rollback_only_failure)))
           :named rollback_only_definition))
(assert (! (= outer_commit_fails
              (or (= scenario sc_outer_commit_recovery_success)
                  (= scenario sc_outer_commit_recovery_failure)))
           :named outer_commit_fails_definition))
(assert (! (= outer_rollback_fails
              (or (= scenario sc_outer_body_rollback_failure)
                  (= scenario sc_outer_commit_recovery_failure)
                  (= scenario sc_outer_rollback_only_failure)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_failure)))
           :named outer_rollback_fails_definition))
(assert (! (= nested_present
              (or (= scenario sc_nested_body_recovered)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_success)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)
                  (= scenario sc_caught_nested_completion_recovered)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_success)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_failure)))
           :named nested_present_definition))
(assert (! (= nested_body_failed
              (or (= scenario sc_nested_body_recovered)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_success)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)))
           :named nested_body_failed_definition))
(assert (! (= nested_rollback_succeeds nested_body_failed)
           :named nested_rollback_succeeds_definition))
(assert (! (= nested_release_after_rollback_fails
              (or (= scenario sc_caught_nested_cleanup_outer_rollback_success)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)))
           :named nested_release_failure_definition))
(assert (! (= nested_completion_fails
              (or (= scenario sc_caught_nested_completion_recovered)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_success)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_failure)))
           :named nested_completion_fails_definition))
(assert (! (= nested_completion_recovery_fails
              (or (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_success)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_failure)))
           :named nested_completion_recovery_fails_definition))
(assert (! (= nested_error_caught nested_present)
           :named nested_error_caught_definition))

;; Branch-derived implementation transitions. impl_prior_guard has the corrected
;; transaction algorithm but deliberately lacks the caught-window user gate.
(declare-const corrected_algorithm Bool)
(declare-const entered Bool)
(declare-const physical_after_begin Physical)
(declare-const bookkeeping_exits Int)
(declare-const post_depth Int)
(declare-const cleanup_error_escapes_body Bool)
(declare-const body_error_primary Bool)
(declare-const release_after_rollback_attempted Bool)
(declare-const nested_cleanup_failed_observed Bool)
(declare-const nested_cleanup_recorded Bool)
(declare-const caught_cleanup_window Bool)
(declare-const intermediate_pending_cleanup Bool)
(declare-const intermediate_poisoned Bool)
(declare-const intermediate_usable Bool)
(declare-const intermediate_user_operation_allowed Bool)
(declare-const intermediate_internal_control_allowed Bool)
(declare-const outer_commit_attempted Bool)
(declare-const outer_rollback_attempted Bool)
(declare-const outer_cleanup_succeeded Bool)
(declare-const outer_cleanup_failed_observed Bool)
(declare-const cleanup_recorded Bool)
(declare-const final_physical Physical)
(declare-const final_pending_cleanup Bool)
(declare-const poisoned Bool)
(declare-const usable Bool)
(declare-const final_user_operation_allowed Bool)
(declare-const final_internal_control_allowed Bool)

(assert (! (= corrected_algorithm (not (= impl impl_old)))
           :named corrected_algorithm_definition))
(assert (! (= entered outer_begin_ok)
           :named entered_definition))
(assert (! (= physical_after_begin
              (ite entered physical_open physical_idle))
           :named physical_after_begin_definition))
(assert (! (= bookkeeping_exits
              (ite (not entered)
                   0
                   (ite (and (= impl impl_old)
                             (or outer_commit_fails
                                 (and rollback_only outer_rollback_fails)
                                 nested_completion_fails))
                        2
                        1)))
           :named bookkeeping_exits_definition))
(assert (! (= post_depth
              (ite entered (- 1 bookkeeping_exits) 0))
           :named post_depth_definition))
(assert (! (= cleanup_error_escapes_body
              (and (= impl impl_old)
                   outer_body_failed
                   outer_rollback_fails))
           :named cleanup_error_escapes_body_definition))
(assert (! (= body_error_primary
              (or (not outer_body_failed)
                  (not cleanup_error_escapes_body)))
           :named body_error_primary_definition))
(assert (! (= release_after_rollback_attempted
              (and corrected_algorithm
                   nested_body_failed
                   nested_rollback_succeeds))
           :named release_after_rollback_attempted_definition))
(assert (! (= nested_cleanup_failed_observed
              (or (and release_after_rollback_attempted
                       nested_release_after_rollback_fails)
                  (and nested_completion_fails
                       nested_completion_recovery_fails)))
           :named nested_cleanup_failed_observed_definition))
(assert (! (= nested_cleanup_recorded
              (and corrected_algorithm
                   nested_cleanup_failed_observed))
           :named nested_cleanup_recorded_definition))
(assert (! (= caught_cleanup_window
              (and nested_error_caught
                   nested_cleanup_failed_observed))
           :named caught_cleanup_window_definition))
(assert (! (= intermediate_pending_cleanup
              (and nested_error_caught
                   nested_cleanup_recorded))
           :named intermediate_pending_cleanup_definition))
(assert (! (= intermediate_poisoned false)
           :named intermediate_poisoned_definition))
(assert (! (= intermediate_usable
              (not intermediate_poisoned))
           :named intermediate_usable_definition))
(assert (! (= intermediate_user_operation_allowed
              (and intermediate_usable
                   (or (not intermediate_pending_cleanup)
                       (= impl impl_old)
                       (= impl impl_prior_guard))))
           :named intermediate_user_operation_allowed_definition))
(assert (! (= intermediate_internal_control_allowed
              (not intermediate_poisoned))
           :named intermediate_internal_control_allowed_definition))
(assert (! (= outer_commit_attempted
              (and entered
                   (not outer_body_failed)
                   (not rollback_only)
                   (not intermediate_pending_cleanup)))
           :named outer_commit_attempted_definition))
(assert (! (= outer_rollback_attempted
              (and entered
                   (or outer_body_failed
                       rollback_only
                       intermediate_pending_cleanup
                       (and outer_commit_attempted outer_commit_fails))))
           :named outer_rollback_attempted_definition))
(assert (! (= outer_cleanup_succeeded
              (and outer_rollback_attempted
                   (not outer_rollback_fails)))
           :named outer_cleanup_succeeded_definition))
(assert (! (= outer_cleanup_failed_observed
              (and outer_rollback_attempted
                   outer_rollback_fails))
           :named outer_cleanup_failed_observed_definition))
(assert (! (= cleanup_recorded
              (and corrected_algorithm
                   (or nested_cleanup_failed_observed
                       outer_cleanup_failed_observed)))
           :named cleanup_recorded_definition))
(assert (! (= final_physical
              (ite (not entered)
                   physical_idle
                   (ite (and outer_commit_attempted
                             (not outer_commit_fails))
                        physical_idle
                        (ite outer_cleanup_succeeded
                             physical_idle
                             physical_unknown))))
           :named final_physical_definition))
(assert (! (= final_pending_cleanup
              (and intermediate_pending_cleanup
                   (not outer_cleanup_succeeded)
                   (not outer_cleanup_failed_observed)))
           :named final_pending_cleanup_definition))
(assert (! (= poisoned
              (and corrected_algorithm
                   outer_cleanup_failed_observed))
           :named poisoned_definition))
(assert (! (= usable (not poisoned))
           :named usable_definition))
(assert (! (= final_user_operation_allowed
              (and usable
                   (not final_pending_cleanup)))
           :named final_user_operation_allowed_definition))
(assert (! (= final_internal_control_allowed
              (not poisoned))
           :named final_internal_control_allowed_definition))

;; Independent scenario-table reference relation.
(declare-const ref_bookkeeping_exits Int)
(declare-const ref_post_depth Int)
(declare-const ref_physical_after_begin Physical)
(declare-const ref_body_error_primary Bool)
(declare-const ref_release_after_rollback_attempted Bool)
(declare-const ref_nested_cleanup_failed_observed Bool)
(declare-const ref_nested_cleanup_recorded Bool)
(declare-const ref_caught_cleanup_window Bool)
(declare-const ref_intermediate_pending_cleanup Bool)
(declare-const ref_intermediate_poisoned Bool)
(declare-const ref_intermediate_usable Bool)
(declare-const ref_intermediate_user_operation_allowed Bool)
(declare-const ref_intermediate_internal_control_allowed Bool)
(declare-const ref_outer_commit_attempted Bool)
(declare-const ref_outer_rollback_attempted Bool)
(declare-const ref_outer_cleanup_succeeded Bool)
(declare-const ref_outer_cleanup_failed_observed Bool)
(declare-const ref_cleanup_recorded Bool)
(declare-const ref_final_physical Physical)
(declare-const ref_final_pending_cleanup Bool)
(declare-const ref_poisoned Bool)
(declare-const ref_usable Bool)
(declare-const ref_final_user_operation_allowed Bool)
(declare-const ref_final_internal_control_allowed Bool)
(declare-const reference_invalid Bool)

(assert (! (= ref_bookkeeping_exits
              (ite (= scenario sc_begin_failure) 0 1))
           :named ref_bookkeeping_exits_definition))
(assert (! (= ref_post_depth 0)
           :named ref_post_depth_definition))
(assert (! (= ref_physical_after_begin
              (ite (= scenario sc_begin_failure)
                   physical_idle
                   physical_open))
           :named ref_physical_after_begin_definition))
(assert (! (= ref_body_error_primary true)
           :named ref_body_error_primary_definition))
(assert (! (= ref_release_after_rollback_attempted
              (or (= scenario sc_nested_body_recovered)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_success)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)))
           :named ref_release_after_rollback_attempted_definition))
(assert (! (= ref_nested_cleanup_failed_observed
              (or (= scenario sc_caught_nested_cleanup_outer_rollback_success)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_success)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_failure)))
           :named ref_nested_cleanup_failed_observed_definition))
(assert (! (= ref_nested_cleanup_recorded
              ref_nested_cleanup_failed_observed)
           :named ref_nested_cleanup_recorded_definition))
(assert (! (= ref_caught_cleanup_window
              ref_nested_cleanup_failed_observed)
           :named ref_caught_cleanup_window_definition))
(assert (! (= ref_intermediate_pending_cleanup
              ref_caught_cleanup_window)
           :named ref_intermediate_pending_cleanup_definition))
(assert (! (= ref_intermediate_poisoned false)
           :named ref_intermediate_poisoned_definition))
(assert (! (= ref_intermediate_usable
              (not ref_intermediate_poisoned))
           :named ref_intermediate_usable_definition))
(assert (! (= ref_intermediate_user_operation_allowed
              (and ref_intermediate_usable
                   (not ref_intermediate_pending_cleanup)))
           :named ref_intermediate_user_operation_allowed_definition))
(assert (! (= ref_intermediate_internal_control_allowed
              (not ref_intermediate_poisoned))
           :named ref_intermediate_internal_control_allowed_definition))
(assert (! (= ref_outer_commit_attempted
              (or (= scenario sc_outer_success)
                  (= scenario sc_outer_commit_recovery_success)
                  (= scenario sc_outer_commit_recovery_failure)
                  (= scenario sc_nested_body_recovered)
                  (= scenario sc_caught_nested_completion_recovered)))
           :named ref_outer_commit_attempted_definition))
(assert (! (= ref_outer_rollback_attempted
              (or (= scenario sc_outer_body_rollback_success)
                  (= scenario sc_outer_body_rollback_failure)
                  (= scenario sc_outer_commit_recovery_success)
                  (= scenario sc_outer_commit_recovery_failure)
                  (= scenario sc_outer_rollback_only_success)
                  (= scenario sc_outer_rollback_only_failure)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_success)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_success)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_failure)))
           :named ref_outer_rollback_attempted_definition))
(assert (! (= ref_outer_cleanup_succeeded
              (and ref_outer_rollback_attempted
                   (not ref_outer_cleanup_failed_observed)))
           :named ref_outer_cleanup_succeeded_definition))
(assert (! (= ref_outer_cleanup_failed_observed
              (or (= scenario sc_outer_body_rollback_failure)
                  (= scenario sc_outer_commit_recovery_failure)
                  (= scenario sc_outer_rollback_only_failure)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_failure)))
           :named ref_outer_cleanup_failed_observed_definition))
(assert (! (= ref_cleanup_recorded
              (or (= scenario sc_outer_body_rollback_failure)
                  (= scenario sc_outer_commit_recovery_failure)
                  (= scenario sc_outer_rollback_only_failure)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_success)
                  (= scenario sc_caught_nested_cleanup_outer_rollback_failure)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_success)
                  (= scenario
                     sc_caught_nested_completion_cleanup_outer_rollback_failure)))
           :named ref_cleanup_recorded_definition))
(assert (! (= ref_final_physical
              (ite ref_outer_cleanup_failed_observed
                   physical_unknown
                   physical_idle))
           :named ref_final_physical_definition))
(assert (! (= ref_final_pending_cleanup false)
           :named ref_final_pending_cleanup_definition))
(assert (! (= ref_poisoned
              ref_outer_cleanup_failed_observed)
           :named ref_poisoned_definition))
(assert (! (= ref_usable
              (not ref_poisoned))
           :named ref_usable_definition))
(assert (! (= ref_final_user_operation_allowed
              (and ref_usable
                   (not ref_final_pending_cleanup)))
           :named ref_final_user_operation_allowed_definition))
(assert (! (= ref_final_internal_control_allowed
              (not ref_poisoned))
           :named ref_final_internal_control_allowed_definition))
(assert (! (= reference_invalid
              (or (not (= ref_post_depth 0))
                  (and ref_intermediate_poisoned
                       ref_intermediate_usable)
                  (and ref_caught_cleanup_window
                       ref_intermediate_user_operation_allowed)
                  (and ref_intermediate_pending_cleanup
                       (not ref_intermediate_internal_control_allowed))
                  (and ref_intermediate_pending_cleanup
                       ref_outer_commit_attempted)
                  (and ref_outer_cleanup_failed_observed
                       ref_final_pending_cleanup)
                  (and ref_poisoned ref_final_pending_cleanup)
                  (and (= ref_final_physical physical_unknown)
                       ref_usable)
                  (and ref_poisoned
                       ref_final_user_operation_allowed)
                  (and ref_poisoned
                       ref_final_internal_control_allowed)))
           :named reference_invalid_definition))

;; Counterexample query: any implementation/reference difference, caught-window
;; fail-open operation, blocked internal recovery, or unsafe final state.
(declare-const violation Bool)
(assert (! (= violation
              (or (not (= bookkeeping_exits ref_bookkeeping_exits))
                  (not (= post_depth ref_post_depth))
                  (not (= physical_after_begin
                          ref_physical_after_begin))
                  (and outer_body_failed
                       (not (= body_error_primary ref_body_error_primary)))
                  (not (= release_after_rollback_attempted
                          ref_release_after_rollback_attempted))
                  (not (= nested_cleanup_recorded
                          ref_nested_cleanup_recorded))
                  (not (= caught_cleanup_window
                          ref_caught_cleanup_window))
                  (not (= intermediate_pending_cleanup
                          ref_intermediate_pending_cleanup))
                  (not (= intermediate_poisoned
                          ref_intermediate_poisoned))
                  (not (= intermediate_usable
                          ref_intermediate_usable))
                  (not (= intermediate_user_operation_allowed
                          ref_intermediate_user_operation_allowed))
                  (not (= intermediate_internal_control_allowed
                          ref_intermediate_internal_control_allowed))
                  (not (= outer_commit_attempted
                          ref_outer_commit_attempted))
                  (not (= outer_rollback_attempted
                          ref_outer_rollback_attempted))
                  (not (= outer_cleanup_succeeded
                          ref_outer_cleanup_succeeded))
                  (not (= outer_cleanup_failed_observed
                          ref_outer_cleanup_failed_observed))
                  (not (= cleanup_recorded ref_cleanup_recorded))
                  (not (= final_physical ref_final_physical))
                  (not (= final_pending_cleanup
                          ref_final_pending_cleanup))
                  (not (= poisoned ref_poisoned))
                  (not (= usable ref_usable))
                  (not (= final_user_operation_allowed
                          ref_final_user_operation_allowed))
                  (not (= final_internal_control_allowed
                          ref_final_internal_control_allowed))
                  (and caught_cleanup_window
                       intermediate_user_operation_allowed)
                  (and intermediate_pending_cleanup
                       (not intermediate_internal_control_allowed))
                  (and outer_cleanup_failed_observed
                       final_pending_cleanup)
                  (and poisoned final_pending_cleanup)
                  (and (= final_physical physical_unknown)
                       usable)
                  (and poisoned final_user_operation_allowed)
                  (and poisoned final_internal_control_allowed)))
           :named violation_definition))

;; Chiasmus adds check-sat/model/core commands. SAT would be a fixed-algorithm
;; counterexample; the checked-in query must be UNSAT.
(assert (! (= impl impl_fixed) :named implementation_under_test))
(assert (! violation :named violation_query))
