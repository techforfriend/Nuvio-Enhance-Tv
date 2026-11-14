package com.nuvio.tv.core.profile

import com.nuvio.tv.domain.model.ContentRatingCap
import io.mockk.coEvery
import io.mockk.mockk

/** An [ActiveRatingCap] whose active profile has [cap] (null: uncapped). */
fun testRatingCap(cap: ContentRatingCap? = null): ActiveRatingCap =
    mockk { coEvery { current() } returns cap }
