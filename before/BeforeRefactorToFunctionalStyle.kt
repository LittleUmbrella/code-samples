/*
 * Copyright 2019 Expedia Group. All rights reserved.
 * EXPEDIA PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package com.expedia.gco.seva.lodging.content.mappers

import com.expedia.gco.commons.mappers.AbstractTripleMapper
import com.expedia.gco.seva.fulfillment.commons.exception.SevaErrorCategory
import com.expedia.gco.seva.fulfillment.commons.exception.SevaErrorCodes
import com.expedia.gco.seva.fulfillment.commons.exception.SevaException
import com.expedia.gco.seva.lodging.content.config.AmenityCategoryImageConfigurations
import com.expedia.gco.seva.lodging.content.query.model.AmenityCategory
import com.expedia.gco.seva.lodging.content.query.model.AmenityType
import com.expedia.gco.seva.lodging.content.query.model.Image
import com.expedia.gco.seva.lodging.content.query.model.Orientation
import com.expedia.mozart.proxies.lodgingcontent.datamodels.MediaDataModel
import com.expedia.mozart.proxies.lodgingcontent.datamodels.PropertyContentLocationDataModel
import com.expedia.mozart.proxies.lodgingcontent.datamodels.PropertyContentRootResponseDataModel
import org.apache.commons.collections4.CollectionUtils
import java.lang.NumberFormatException
import javax.inject.Named

private const val TEMPORARY_WIDTH = 500
private const val TEMPORARY_HEIGHT = 300
private const val TEMPORARY_AESTHETIC_SCORE = 0.94
private const val INVALID_ROOM_ID = "roomId is not valid"
@Named
class AmenityImageMapMapper(amenityCategoryImageConfiguration: AmenityCategoryImageConfigurations)
    : AbstractTripleMapper<
        PropertyContentRootResponseDataModel,
        List<String>,
        String,
        Map<AmenityCategory, List<Image>>?>() {

    private var sectionIdToAmenityCategoryMap: HashMap<Int, MutableList<AmenityCategory>> = HashMap()

    init {
        // transform the map to each subjectId maps to a list of amenity categories
        transformMapToUseSubjectIdAsKey(amenityCategoryImageConfiguration.lcsMediaConfigurations)
    }

    private fun transformMapToUseSubjectIdAsKey(lcsMediaConfigurations: Map<AmenityCategory, List<Int>>) {
        for (amenity in lcsMediaConfigurations) {
            for (subjectId in amenity.value) {
                if (!sectionIdToAmenityCategoryMap.containsKey(subjectId)) {
                    sectionIdToAmenityCategoryMap[subjectId] = mutableListOf()
                }
                sectionIdToAmenityCategoryMap[subjectId]?.add(amenity.key)
            }
        }
    }

    override fun doMap(
        lcsResponse: PropertyContentRootResponseDataModel?,
        amenityCategories: List<String>?,
        roomId: String?
    ): Map<AmenityCategory, List<Image>>? {

        if (lcsResponse == null || CollectionUtils.isEmpty(lcsResponse.propertyContents) ||
                CollectionUtils.isEmpty(amenityCategories))
            return null

        val amenityResponseMap = HashMap<AmenityCategory, MutableList<Image>>()
        for (amenity in amenityCategories!!) {
            val amenityCategory = getAmenityCategory(amenity)
            if (amenityResponseMap[amenityCategory] == null) {
                amenityResponseMap[amenityCategory] = mutableListOf()
            }
        }

        val alreadyAddedImages = HashSet<Int>()

        val propertyContent = lcsResponse.propertyContents.firstOrNull()!!

        if (roomId != null) {
            getRoomAmenityImages(propertyContent, roomId, amenityResponseMap, alreadyAddedImages)
        }

        val propertyMediaList = propertyContent.medias
        if (!CollectionUtils.isEmpty(propertyMediaList)) {
            addImagesFromMediaList(propertyMediaList, amenityResponseMap, alreadyAddedImages, AmenityType.PROPERTY)
        }

        return amenityResponseMap
    }

    private fun getRoomAmenityImages(
        propertyContent: PropertyContentLocationDataModel,
        roomId: String,
        amenityResponseMap: HashMap<AmenityCategory, MutableList<Image>>,
        alreadyAddedImages: HashSet<Int>
    ) {
        val roomsList = propertyContent.roomTypeContents
        if (roomsList != null) {
            try {
                val roomContent = roomsList.filter { it.roomTypeContentIdd == roomId.toInt() }.firstOrNull()
                if (roomContent != null && !CollectionUtils.isEmpty(roomContent.medias)) {
                    addImagesFromMediaList(
                            roomContent.medias,
                            amenityResponseMap,
                            alreadyAddedImages,
                            AmenityType.ROOM_UNIT
                    )
                }
            } catch (ex: NumberFormatException) {
                throw SevaException(INVALID_ROOM_ID,
                        SevaErrorCodes.INVALID_REQUEST,
                        SevaErrorCategory.BUSINESS_ERROR)
            }
        }
    }

    private fun addImagesFromMediaList(
        medias: List<MediaDataModel>,
        amenityResponseMap: HashMap<AmenityCategory, MutableList<Image>>,
        alreadyAddedImages: HashSet<Int>,
        amenityType: AmenityType
    ) {
        for (media in medias) {
            if (!alreadyAddedImages.contains(media.mediaId)) {
                alreadyAddedImages.add(media.mediaId)
                if (amenityIsRequested(sectionIdToAmenityCategoryMap[media.subjectId], amenityResponseMap)) {
                    // Waiting for Product to figure out best size to map image
                    // https://jira.expedia.biz/browse/VAP-13516
                    addImage(media, amenityResponseMap, amenityType)
                }
            }
        }
    }

    private fun addImage(
        media: MediaDataModel,
        amenityResponseMap: HashMap<AmenityCategory, MutableList<Image>>,
        amenityType: AmenityType
    ) {

        // Use a fake image for now
        val image = Image(
                url = media.caption,
                orientation = Orientation.LANDSCAPE,
                width = TEMPORARY_WIDTH,
                height = TEMPORARY_HEIGHT,
                aestheticScore = TEMPORARY_AESTHETIC_SCORE.toFloat(),
                amenityType = amenityType
        )

        for (amenity in sectionIdToAmenityCategoryMap[media.subjectId]!!) {
            if (amenityResponseMap.containsKey(amenity))
                amenityResponseMap[amenity]!!.add(image)
        }
    }

    private fun amenityIsRequested(
        amenityList: List<AmenityCategory>?,
        amenityResponseMap: HashMap<AmenityCategory, MutableList<Image>>
    ): Boolean {
        for (amenity in amenityList!!) {
            if (amenityResponseMap.containsKey(amenity))
                return true
        }
        return false
    }

    private fun getAmenityCategory(filterAmenityCategory: String): AmenityCategory {
        try {
            return AmenityCategory.valueOf(filterAmenityCategory)
        } catch (exception: IllegalArgumentException) {
            throw SevaException("Lodging amenity query not supported.", SevaErrorCodes.UNSUPPORTED_ACTION,
                    SevaErrorCategory.BUSINESS_ERROR)
        }
    }
}