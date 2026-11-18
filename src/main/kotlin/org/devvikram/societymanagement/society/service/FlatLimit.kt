package org.devvikram.societymanagement.society.service

// A check before flats are added to a society (implemented by the subscription module: a plan may cap the
// number of flats). Throws when the society may not have that many more.
fun interface FlatLimit {
    fun checkRoom(societyId: Long, adding: Int)
}
