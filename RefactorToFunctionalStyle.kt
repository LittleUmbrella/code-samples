/*
 * Copyright 2019 Expedia Group. All rights reserved.
 * EXPEDIA PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package com.expedia.gco.seva.lodging.content.mappers

import com.expedia.gco.commons.mappers.AbstractTripleMapper
import com.expedia.gco.seva.lodging.content.config.AmenityCategoryImageConfigurations
import com.expedia.gco.seva.lodging.content.query.model.AmenityCategory
import com.expedia.gco.seva.lodging.content.query.model.AmenityType
import com.expedia.gco.seva.lodging.content.query.model.Image
import com.expedia.mozart.proxies.lodgingcontent.datamodels.MediaDataModel
import com.expedia.mozart.proxies.lodgingcontent.datamodels.PropertyContentRootResponseDataModel
import org.apache.commons.collections4.CollectionUtils
import org.springframework.beans.factory.annotation.Value
import javax.inject.Named

/*
The LCS response looks something like this:

{
propertyContents: {
    medias: [
        {
            subjectId: 81003,
            media: {
                id: 3241,
                sizes: [
                    url: "http://randoimage.com/1",
                ...
                ]
            }
        },
        {
            subjectId: 81012,
            media: {
                id: 65443,
                sizes: [
                    url: "http://randoimage.com/3",
                ...
                ]
            }
        },
        ...
    ]
    ,
    roomTypeContents: {
        medias: [
            {
                subjectId: 81003,
                media: {
                    id: 3241,
                    sizes: [
                        url: "http://randoimage.com/1",
                        ...
                    ]
                }
            },
            {
                subjectId: 81004,
                media: {
                    id: 2423,
                    sizes: [
                        url: "http://randoimage.com/2",
                        ...
                    ]
                }
            },
            ...
        ]
    }
}

our final output will look like this (a map, but visualized as json):

[
    {
        key: "RESTAURANT_IN_HOTEL",
        value: [
            {
                url: "http://randoimage.com/1",
                width: 300,
                height: 200,
                ...
            },
            {
                url: "http://randoimage.com/1",
                width: 300,
                height: 200,
                ...
            },
            ...
        ]
    },
    {
        key: "BREAKFAST",
        value: [
            {
                url: "http://randoimage.com/3",
                width: 300,
                height: 200,
                ...
            },
            ...
        ]
    },
    ...
]

 */

internal const val SIZE_500X500V = 14
internal const val SIZE_1000X1000V = 15
internal const val SIZE_160X90F = 16
internal const val SIZE_2000X2000V = 17
@Named
class AmenityImageMapMapper(
    amenityCategoryImageConfiguration: AmenityCategoryImageConfigurations,
    @Value("\${amenityImageBaseUrl}") private var imageBaseUrl: String
)
    : AbstractTripleMapper<
        PropertyContentRootResponseDataModel?,
        List<AmenityCategory>,
        Int, // RoomId
        Map<AmenityCategory, List<Image>>?>() {

    private val amenitySubcategoryToCategoryMap: HashMap<Int, List<AmenityCategory>> = HashMap()

    init {
        /*
        The LCS response images (see above) are grouped by subject/subcategory id.  Since our mapped output (see above)
        is by category, as we loop the LCS response, we need to look up which category each subcategory belongs to.

        In order to be efficient, we build a hashmap - key = subject/subcategory, value = each category it falls under.
        This happens to be the flip of how we configure in the application.yml.  It's easier to maintain that way, but
        not efficient for the work this mapper has to do.

        So we flip the configured map:
         */
        amenitySubcategoryToCategoryMap
            .putAll(
                transformMap(amenityCategoryImageConfiguration.lcsMediaConfigurations)
            )
    }

    private fun transformMap(lcsMediaConfigurations: Map<AmenityCategory, List<Int>>): Map<Int, List<AmenityCategory>> {

        return lcsMediaConfigurations
            /*
            first we take the structure (see application.yml for full config) of

            BREAKFAST:
              - 81012 #Food and Drink
              - 81003 #Restaurant
            RESTAURANT_IN_HOTEL:
              - 81003 #Restaurant
              - 81004 #Buffet

            and flatten it to (note the duplicate 81003)

            - 81012, BREAKFAST
            - 81003, BREAKFAST
            - 81003, RESTAURANT_IN_HOTEL
            - 81004, RESTAURANT_IN_HOTEL
             */
            .flatMap { entry: Map.Entry<AmenityCategory, List<Int>> ->
                entry.value.map { Pair(it, entry.key) }
            }
            /*
            then we collapse (group/dedupe) the flat list above to
            - 81012,
                - BREAKFAST
            - 81003,
                - BREAKFAST
                - RESTAURANT_IN_HOTEL
            - 81004,
                - RESTAURANT_IN_HOTEL
             */
            .groupBy(
                { it.first /* Amenity Subcategory */ },
                { it.second /* Amenity Category, listified in groupBy */ }
            )
    }

    override fun doMap(
        lcsResponse: PropertyContentRootResponseDataModel?,
        amenityCategoriesFilterList: List<AmenityCategory>,
        roomId: Int
    ): Map<AmenityCategory, List<Image>> {

        if (
            lcsResponse == null ||
            CollectionUtils.isEmpty(lcsResponse.propertyContents) ||
            CollectionUtils.isEmpty(amenityCategoriesFilterList)
        ) {
            return emptyMap()
        }

        /*
        Images must be arranged in a nested fashion (see final output above), so to eliminate duplicates as we filter
        the LCS response, it's handy/efficient to have a local hashset of images already processed.

        Note, images are presumed to be unique within a single room.  The dupes would be in the set of room plus
        property images
        */
        val alreadyAddedImages = HashSet<Int>()

        // Lambda for use in the lines immediately following
        val buildImageMapFromMediaList = {
            medias: List<MediaDataModel>? /* will be the room or property 'medias' from the LCS response (see above) */,
            amenityType: AmenityType /* room or property */ ->

            medias
                ?.mapNotNull { mediaDataModel ->
                    // Some subcategories/subjectids may not be configured, so we have to make sure they are and then
                    // get the categories
                    val amenityCategoryList = amenitySubcategoryToCategoryMap[mediaDataModel.subjectId]

                    // Create a Pair of the context mediaDataModel and the amenityCategoryList we just found
                    // if not null
                    amenityCategoryList?.let { Pair(mediaDataModel, amenityCategoryList) }
                }
                /*
                    Reverse and flatten out the categories to a list of repeating AmenityCategory over the medias
                    This will look like this:
                    - BREAKFAST, MediaDataModel1
                    - BREAKFAST, MediaDataModel2
                    - RESTAURANT_IN_HOTEL, MediaDataModel1
                    - RESTAURANT_IN_HOTEL, MediaDataModel3
                    ...
                */
                ?.flatMap { pair: Pair<MediaDataModel, List<AmenityCategory>> ->
                    pair.second.map { Pair(it, pair.first) }
                }
                ?.filter {
                    amenityCategoriesFilterList.contains(it.first) && !alreadyAddedImages.contains(it.second.mediaId) &&
                            // we only want images of size 14, 15 or 17
                            it.second.derivatives.any { it.size == SIZE_500X500V || it.size == SIZE_1000X1000V || it.size == SIZE_2000X2000V }
                }
                ?.groupBy(
                    { it.first /* Amenity Category */ },
                    {
                        it.second.derivatives.sortByDescending { it.size }
                        // the first image on the sorted list which is not size of 16
                        val bestImage = it.second.derivatives.find { it.size != SIZE_160X90F }!!
                        val image = Image(
                            url = imageBaseUrl.plus(bestImage.name),
                            aestheticScore = it.second.aestheticScore.score,
                            amenityType = amenityType,
                            displayText = it.second.display
                        )

                        // take this opportunity to add to the alreadyAddedImages
                        alreadyAddedImages.add(it.second.mediaId)

                        image
                    }
                )
        }

        // Safe because of sanity check in the beginning of this method
        val lowestCommonAncestorOfAllMediaInLCS = lcsResponse.propertyContents.first()

        val roomImageMap = buildImageMapFromMediaList(
            lowestCommonAncestorOfAllMediaInLCS
                ?.roomTypeContents
                ?.filter { it.roomTypeContentIdd == roomId }
                ?.firstOrNull() // There should only ever be one room with the given id
                ?.medias,
            AmenityType.ROOM_UNIT
        )

        val propertyImageMap = buildImageMapFromMediaList(
            lowestCommonAncestorOfAllMediaInLCS?.medias,
            AmenityType.PROPERTY
        )

        return roomImageMap.mergeReduce(
            other = propertyImageMap,
            // override default so the values are union-ed (no need for deduping, that is handled by logic around
            // alreadyAddedImages, see above)
            reduce = { otherMapEntryValue, thisMapEntryValue -> thisMapEntryValue.plus(otherMapEntryValue) }
        ) ?: emptyMap()
    }
}

/**
 * Merges two maps creating a union, allowing the caller to dictate how values are merged.
 *
 * @param K - the type of the key for [this] and the passed map [other].
 * @param V - the type of the value for [this] and the passed map [other].
 * @param other - the map to merge with [this].
 * @param reduce - a lambda that receives the value of [this] and the [other]'s value and returns [V].  Default is to
 * take the value of [this] map entry if keys match
 * @return new map, the union of [this] and the passed [other].
 */
private fun <K, V> Map<K, V>?.mergeReduce(
    other: Map<K, V>?,
    reduce: (V, V) -> V = { otherMapEntryValue, thisMapEntryValue -> thisMapEntryValue }
): Map<K, V>? {
    if (other == null) return this

    val result = LinkedHashMap<K, V>((this?.size ?: 0) + other.size)
    if (this != null) {
        result.putAll(this)
    }
    other.forEach { e ->
        result[e.key] = result[e.key]?.let {
            reduce(e.value, it)
        } ?: e.value
    }

    return result
}
