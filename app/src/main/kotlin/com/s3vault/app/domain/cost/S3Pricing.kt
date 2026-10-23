package com.s3vault.app.domain.cost

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One tier of volume pricing: applies to the storage up to [upToGb] (null = no upper limit). 1 TB = 1024 GB in AWS pricing. */
data class Tier(val upToGb: Int?, val pricePerGb: Double)

/**
 * On-Demand list prices (USD) of one region, as published by AWS. Flat-priced classes are a single number; null means
 * the price list has no such product for the region (the estimator then refuses to price that class instead of guessing).
 * Generated into [S3_PRICES]; see tools/update_s3_prices.py.
 */
data class S3RegionPrices(
    /** S3 Standard, per GB-month, by tier. */
    val standard: List<Tier>,
    val standardIa: Double?,
    val oneZoneIa: Double?,
    val glacierIr: Double?,
    val glacierFlexible: Double?,
    /** Intelligent-Tiering Frequent Access tier (the most expensive one: an upper bound for the class). */
    val intelligentFrequent: List<Tier>,
    val intelligentMonitoringPerObject: Double?,
    /** PUT, COPY, POST and LIST requests of S3 Standard (per single request). */
    val putPerRequest: Double?,
    /** GET and all other requests (HEAD...) of S3 Standard (per single request). */
    val getPerRequest: Double?,
    val retrievalStandardIaPerGb: Double?,
    val retrievalOneZoneIaPerGb: Double?,
    val retrievalGlacierIrPerGb: Double?,
)

/**
 * The only source of prices in the app: a table that ships with it. Nothing is downloaded at run time and no bucket
 * information leaves the device. The table says which AWS price list it comes from and the UI warns when it is old.
 */
object S3Pricing {
    /** Publication date of the AWS price list behind the table. */
    val published: LocalDate = LocalDate.parse(S3_PRICE_LIST_PUBLISHED)

    /** Day the table was generated. */
    val generated: LocalDate = LocalDate.parse(S3_PRICE_TABLE_GENERATED)

    const val SOURCE_NAME = "AWS Price List Bulk API (oferta AmazonS3)"
    const val SOURCE_URL = "https://aws.amazon.com/s3/pricing/"

    /** Past this age the prices are shown with a warning and the confidence drops. */
    const val AGED_AFTER_DAYS = 180L

    /** Past this age the confidence is low. */
    const val OLD_AFTER_DAYS = 365L

    val regions: Set<String> get() = S3_PRICES.keys

    fun forRegion(region: String): S3RegionPrices? = S3_PRICES[region.trim().lowercase()]

    fun ageDays(today: LocalDate, publishedOn: LocalDate = published): Long = ChronoUnit.DAYS.between(publishedOn, today).coerceAtLeast(0)
}
