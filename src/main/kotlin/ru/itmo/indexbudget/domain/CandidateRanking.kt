package ru.itmo.indexbudget.domain

/** Orders scenario shortlist queues only; never changes objective metrics or set evaluation. */
enum class CandidateRanking(val wireName: String) {
    FREQUENCY("frequency"),
    BASELINE_COST("baseline_cost"),
}
