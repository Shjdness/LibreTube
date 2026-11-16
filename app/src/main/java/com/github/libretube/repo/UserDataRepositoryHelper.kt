package com.github.libretube.repo

object UserDataRepositoryHelper {
    val userDataRepository: UserDataRepository = LocalUserDataRepository()
    val feedRepository: FeedRepository = LocalFeedRepository()
}
