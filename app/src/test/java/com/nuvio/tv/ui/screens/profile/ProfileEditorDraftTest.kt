package com.nuvio.tv.ui.screens.profile

import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.domain.model.ContentRatingCap
import com.nuvio.tv.domain.model.LocalLookSets
import com.nuvio.tv.domain.model.ProfileBackgroundSelection
import com.nuvio.tv.domain.model.ProfileLookChoice
import com.nuvio.tv.domain.model.ProfileLookEntry
import com.nuvio.tv.domain.model.ProfileLookPick
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.AvatarArt
import com.nuvio.tv.data.local.MyPhoto
import com.nuvio.tv.data.local.MyPhotoKind
import com.nuvio.tv.ui.components.AvatarTile
import com.nuvio.tv.ui.components.LocalLookLibrary
import java.io.File
import kotlin.time.Instant
import com.nuvio.tv.ui.components.BundledLookSets
import com.nuvio.tv.ui.components.ProfileBackgroundPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditorDraftTest {

    private val kira = AvatarCatalogItem("kira", "Kira", "https://img/kira.png", "Animation", 1, bgColor = "#43A047")
    private val thom = AvatarCatalogItem("thom", "Thom", "https://img/thom.png", "Animation", 2, bgColor = "#E39B2D")
    private val plain = AvatarCatalogItem("plain", "Plain", "https://img/plain.png", "Animation", 3)
    private val catalog = listOf(kira, thom, plain)
    private val sara = UserProfile(
        id = 2,
        name = "Sara",
        avatarColorHex = "#C2185B",
        usesPrimaryAddons = true,
        avatarId = "custom-sara",
        avatarUrl = "https://img/sara-own.png",
        profileBackgroundUrl = "https://img/sara-bg.png"
    )

    @Test
    fun `create starts empty on the default colour and needs a name`() {
        val draft = ProfileEditorDraft.create()
        assertTrue(draft.isCreate)
        assertEquals("", draft.name)
        assertEquals(ProfileEditorDraft.DEFAULT_COLOR, draft.colorHex)
        assertFalse(draft.canSave)
        assertFalse(draft.hasChanges)
        draft.updateName("  ")
        assertFalse(draft.canSave)
        assertTrue(draft.hasChanges)
    }

    @Test
    fun `a picked avatar alone is a change on create`() {
        val draft = ProfileEditorDraft.create()
        draft.toggleAvatar(thom)
        assertTrue(draft.hasChanges)
        draft.toggleAvatar(thom)
        assertFalse(draft.hasChanges)
    }

    @Test
    fun `a background-only edit is a change until it returns to the original`() {
        val draft = ProfileEditorDraft.edit(sara, liveTvEnabled = true, ratingCap = null, look = null, sets = BundledLookSets)
        draft.toggleCatalogBackground("dunes")
        assertTrue(draft.hasChanges)
        draft.toggleCustomBackground()
        assertFalse(draft.hasChanges)
    }

    @Test
    fun `without a custom background the custom toggle only clears a catalog pick`() {
        val draft = ProfileEditorDraft.edit(sara.copy(profileBackgroundUrl = null), liveTvEnabled = true, ratingCap = null, look = null, sets = BundledLookSets)
        draft.toggleCustomBackground()
        assertEquals(null to null, draft.backgroundId to draft.backgroundUrl)
        draft.toggleCatalogBackground("dunes")
        draft.toggleCustomBackground()
        assertEquals(null to null, draft.backgroundId to draft.backgroundUrl)
    }

    @Test
    fun `names over twenty characters are ignored`() {
        val draft = ProfileEditorDraft.create()
        draft.updateName("a".repeat(20))
        draft.updateName("a".repeat(21))
        assertEquals("a".repeat(20), draft.name)
    }

    @Test
    fun `picking the selected avatar again restores the starting colour`() {
        val create = ProfileEditorDraft.create()
        create.toggleAvatar(thom)
        assertEquals("thom" to "#E39B2D", create.avatarId to create.colorHex)
        create.toggleAvatar(thom)
        assertEquals(null to ProfileEditorDraft.DEFAULT_COLOR, create.avatarId to create.colorHex)

        val edit = ProfileEditorDraft.edit(sara, liveTvEnabled = true, ratingCap = null, look = null, sets = BundledLookSets)
        edit.toggleAvatar(thom)
        edit.toggleAvatar(thom)
        assertEquals("#C2185B", edit.colorHex)
    }

    @Test
    fun `an avatar without a colour keeps the current one`() {
        val draft = ProfileEditorDraft.create()
        draft.toggleAvatar(thom)
        draft.toggleAvatar(plain)
        assertEquals("plain" to "#E39B2D", draft.avatarId to draft.colorHex)
    }

    @Test
    fun `preview follows focus, then falls back to the selection`() {
        val draft = ProfileEditorDraft.create()
        draft.toggleAvatar(thom)
        draft.focusTile(AvatarTile.Server(kira))
        assertEquals(AvatarArt.Image("https://img/kira.png"), draft.previewArt(catalog) { null })
        assertEquals("#43A047", draft.previewColorHex)
        draft.focusTile(null)
        assertEquals(AvatarArt.Image("https://img/thom.png"), draft.previewArt(catalog) { null })
        assertEquals("#E39B2D", draft.previewColorHex)
    }

    @Test
    fun `an unchanged edit keeps the profile's own avatar image until the avatar changes`() {
        val draft = ProfileEditorDraft.edit(sara, liveTvEnabled = true, ratingCap = null, look = null, sets = BundledLookSets)
        assertEquals(AvatarArt.Image("https://img/sara-own.png"), draft.selectedArt(catalog) { null })
        draft.toggleAvatar(kira)
        assertEquals(AvatarArt.Image("https://img/kira.png"), draft.selectedArt(catalog) { null })
        draft.toggleAvatar(kira)
        assertNull(draft.selectedArt(catalog) { "resolved" })
    }

    @Test
    fun `an edit without its own url resolves the original avatar id`() {
        val draft = ProfileEditorDraft.edit(sara.copy(avatarUrl = null), liveTvEnabled = true, ratingCap = null, look = null, sets = BundledLookSets)
        assertEquals(AvatarArt.Image("resolved:custom-sara"), draft.selectedArt(catalog) { "resolved:$it" })
    }

    @Test
    fun `toProfile keeps other fields and drops the own avatar url only when the avatar changed`() {
        val draft = ProfileEditorDraft.edit(sara, liveTvEnabled = true, ratingCap = null, look = null, sets = BundledLookSets)
        assertFalse(draft.hasChanges)
        assertEquals(sara, draft.toProfile())

        draft.updateName("Sara K")
        draft.toggleAvatar(kira)
        val saved = draft.toProfile()
        assertTrue(draft.hasChanges)
        assertEquals(
            sara.copy(name = "Sara K", avatarId = "kira", avatarColorHex = "#43A047", avatarUrl = null),
            saved
        )
    }

    @Test
    fun `a stored blank background url is no change`() {
        val draft = ProfileEditorDraft.edit(sara.copy(profileBackgroundUrl = ""), liveTvEnabled = true, ratingCap = null, look = null, sets = BundledLookSets)
        assertNull(draft.backgroundUrl)
        assertFalse(draft.hasChanges)
    }

    @Test
    fun `backgrounds toggle between standard, catalog and the custom url`() {
        val draft = ProfileEditorDraft.edit(sara, liveTvEnabled = true, ratingCap = null, look = null, sets = BundledLookSets)
        assertEquals("https://img/sara-bg.png", draft.backgroundUrl)
        draft.toggleCatalogBackground("dunes")
        assertEquals("dunes" to null, draft.backgroundId to draft.backgroundUrl)
        draft.toggleCatalogBackground("dunes")
        assertEquals(null to null, draft.backgroundId to draft.backgroundUrl)
        draft.toggleCustomBackground()
        assertEquals(null to "https://img/sara-bg.png", draft.backgroundId to draft.backgroundUrl)
        draft.toggleCustomBackground()
        assertNull(draft.backgroundUrl)
        draft.toggleCatalogBackground("dunes")
        draft.selectStandardBackground()
        assertEquals(null to null, draft.backgroundId to draft.backgroundUrl)
    }

    @Test
    fun `live tv switch starts from the stored value, counts as a change, and reports only a real change`() {
        val created = ProfileEditorDraft.create()
        assertTrue(created.liveTvEnabled)
        created.updateLiveTv(false)
        assertTrue(created.hasChanges)
        assertTrue(created.liveTvChanged)

        val edited = ProfileEditorDraft.edit(sara, liveTvEnabled = false, ratingCap = null, look = null, sets = BundledLookSets)
        assertFalse(edited.liveTvEnabled)
        assertFalse(edited.hasChanges)
        edited.updateLiveTv(true)
        assertTrue(edited.hasChanges)
        edited.updateLiveTv(false)
        assertFalse(edited.liveTvChanged)
        assertFalse(edited.hasChanges)
    }

    @Test
    fun `rating limit starts from the stored cap, counts as a change, and reports only a real change (Spec 64)`() {
        val created = ProfileEditorDraft.create()
        assertNull(created.ratingCap)
        created.updateRatingCap(ContentRatingCap.OLDER_KIDS)
        assertTrue(created.ratingCapChanged)
        assertTrue(created.hasChanges)

        val edited = ProfileEditorDraft.edit(sara, liveTvEnabled = true, ratingCap = ContentRatingCap.TEENS, look = null, sets = BundledLookSets)
        assertEquals(ContentRatingCap.TEENS, edited.ratingCap)
        assertFalse(edited.hasChanges)
        // No limit is a change from a limit, not an absent value.
        edited.updateRatingCap(null)
        assertTrue(edited.ratingCapChanged)
        assertTrue(edited.hasChanges)
        edited.updateRatingCap(ContentRatingCap.TEENS)
        assertFalse(edited.ratingCapChanged)
        assertFalse(edited.hasChanges)
    }

    // Spec 44a: the effective avatar pick, the colour rule and the look entry on save.

    private val fox = ProfileLookPick.Bundled("fox")
    private val photo = ProfileLookPick.Photo("p1")
    private val sets = LocalLookSets(photoIds = setOf("p1"), bundledAvatars = setOf("fox"), bundledBackgrounds = emptySet())
    private val omar = UserProfile(id = 3, name = "Omar", avatarColorHex = "#5E35B1", avatarId = "kira")

    private fun openedWith(profile: UserProfile, avatar: ProfileLookPick?): ProfileEditorDraft =
        ProfileEditorDraft.edit(profile, liveTvEnabled = true, ratingCap = null, look = ProfileLookEntry.of(profile, avatar, null), sets = sets)

    @Test
    fun `the draft opens on the local pick that applies, else synced, else none`() {
        assertEquals(EditorAvatarPick.Local(photo), openedWith(omar, photo).avatarPick)
        assertEquals(EditorAvatarPick.Synced, ProfileEditorDraft.edit(omar, true, ratingCap = null, look = null, sets = BundledLookSets).avatarPick)
        // Saved over an older avatar: inert, the synced one opens.
        val stale = ProfileLookEntry(avatar = ProfileLookChoice(photo, "Omar", "thom"))
        assertEquals(EditorAvatarPick.Synced, ProfileEditorDraft.edit(omar, true, ratingCap = null, stale, sets).avatarPick)
        assertEquals(EditorAvatarPick.None, ProfileEditorDraft.edit(omar.copy(avatarId = null), true, ratingCap = null, look = null, sets = BundledLookSets).avatarPick)
        assertEquals(EditorAvatarPick.None, ProfileEditorDraft.create().avatarPick)
    }

    @Test
    fun `re-picking the synced tile over a photo returns to synced and clears the look`() {
        val draft = openedWith(omar, photo)
        assertEquals(AvatarTile.photoKey("p1"), draft.selectedTileKey)
        draft.toggleAvatar(kira)
        assertEquals(EditorAvatarPick.Synced, draft.avatarPick)
        assertEquals("#5E35B1", draft.colorHex)
        assertEquals(AvatarTile.serverKey("kira"), draft.selectedTileKey)
        assertEquals(omar, draft.toProfile())
        assertNull(draft.localAvatar)
        assertTrue(draft.hasChanges)
    }

    @Test
    fun `deselecting a photo returns to synced and writes no synced field`() {
        val draft = openedWith(omar, photo)
        draft.toggleLocalAvatar(photo)
        assertEquals(EditorAvatarPick.Synced, draft.avatarPick)
        assertEquals(omar, draft.toProfile())
        assertNull(draft.localAvatar)
        // On create there is no synced avatar: deselecting gives none.
        val create = ProfileEditorDraft.create()
        create.toggleLocalAvatar(fox)
        create.toggleLocalAvatar(fox)
        assertEquals(EditorAvatarPick.None, create.avatarPick)
        assertFalse(create.hasChanges)
    }

    @Test
    fun `a local-only pick is a change and keeps every synced field`() {
        val draft = ProfileEditorDraft.edit(omar, true, ratingCap = null, sets = sets, look = null)
        assertFalse(draft.hasChanges)
        draft.toggleTile(AvatarTile.Creature(com.nuvio.tv.ui.components.BundledCreatures.bySlug.getValue("fox")))
        assertEquals(EditorAvatarPick.Local(fox), draft.avatarPick)
        assertEquals(AvatarTile.creatureKey("fox"), draft.selectedTileKey)
        assertTrue(draft.hasChanges)
        assertEquals(omar, draft.toProfile())
        assertTrue(ProfileEditorDraft.create().apply { toggleLocalAvatar(fox) }.hasChanges)
    }

    @Test
    fun `a rename while synced is a website url keeps the url`() {
        val draft = ProfileEditorDraft.edit(sara, true, ratingCap = null, look = null, sets = BundledLookSets)
        assertEquals(EditorAvatarPick.Synced, draft.avatarPick)
        draft.updateName("Sara K")
        assertEquals(sara.copy(name = "Sara K"), draft.toProfile())
    }

    @Test
    fun `a rename plus a bundled pick saves the name and keeps the look under the new name`() {
        val draft = ProfileEditorDraft.edit(omar, true, ratingCap = null, sets = sets, look = null)
        draft.updateName("Omar K")
        draft.toggleLocalAvatar(fox)
        val saved = draft.toProfile()
        assertEquals(omar.copy(name = "Omar K"), saved)
        val look = ProfileLookEntry.of(saved, draft.localAvatar, draft.localBackground)
        assertEquals(ProfileLookEntry(avatar = ProfileLookChoice(fox, "Omar K", "kira")), look)
    }

    @Test
    fun `server Y then bundled then deselect restores the opened colour`() {
        val draft = ProfileEditorDraft.edit(omar, true, ratingCap = null, sets = sets, look = null)
        draft.toggleAvatar(thom)
        assertEquals("#E39B2D", draft.colorHex)
        draft.toggleLocalAvatar(fox)
        assertEquals("#5E35B1", draft.colorHex)
        assertEquals("kira", draft.avatarId)
        draft.toggleLocalAvatar(fox)
        assertEquals(EditorAvatarPick.Synced, draft.avatarPick)
        assertEquals("#5E35B1", draft.colorHex)
        assertFalse(draft.hasChanges)
    }

    @Test
    fun `picking the opened synced avatar after another keeps the opened colour`() {
        val draft = ProfileEditorDraft.edit(omar, true, ratingCap = null, look = null, sets = BundledLookSets)
        draft.toggleAvatar(thom)
        draft.toggleAvatar(kira)
        assertEquals(EditorAvatarPick.Synced, draft.avatarPick)
        assertEquals("#5E35B1", draft.colorHex)
        // Picked again it clears, as built.
        draft.toggleAvatar(kira)
        assertEquals(EditorAvatarPick.None, draft.avatarPick)
    }

    @Test
    fun `removing the draft's photo resets it to synced`() {
        val draft = ProfileEditorDraft.edit(omar, true, ratingCap = null, sets = sets, look = null)
        draft.toggleAvatar(thom)
        draft.toggleLocalAvatar(photo)
        draft.onPhotoRemoved("other")
        assertEquals(EditorAvatarPick.Local(photo), draft.avatarPick)
        draft.onPhotoRemoved("p1")
        assertEquals(EditorAvatarPick.Synced, draft.avatarPick)
        assertEquals("#5E35B1", draft.colorHex)
        assertFalse(draft.hasChanges)
    }

    @Test
    fun `an opened local background is kept on save until another background is picked`() {
        val background = ProfileLookPick.Bundled("ember")
        val bgSets = sets.copy(bundledBackgrounds = setOf("ember"))
        val look = ProfileLookEntry.of(sara, null, background)
        val draft = ProfileEditorDraft.edit(sara, true, ratingCap = null, look, bgSets)
        assertEquals(background, draft.localBackground)
        draft.updateName("Sara K")
        assertEquals(sara.copy(name = "Sara K"), draft.toProfile())
        draft.toggleCatalogBackground("dunes")
        assertNull(draft.localBackground)
        assertEquals("dunes" to null, draft.backgroundId to draft.backgroundUrl)
    }

    // Spec 44b: bundled background picks.

    private val ember = ProfileLookPick.Bundled("arch_ember")
    private val ruby = ProfileLookPick.Bundled("infinity_ruby")
    private val nadia = UserProfile(id = 4, name = "Nadia", avatarColorHex = "#00897B", avatarId = "kira", profileBackgroundId = "dunes")

    @Test
    fun `a bundled background toggles on and off back to the synced background`() {
        val draft = ProfileEditorDraft.edit(nadia, true, ratingCap = null, look = null, sets = BundledLookSets)
        assertEquals(ProfileBackgroundPick.Catalog("dunes"), draft.backgroundPick)
        draft.toggleCatalogBackground("stars")
        draft.toggleLocalBackground(ember)
        assertEquals(ProfileBackgroundPick.Local(ember), draft.backgroundPick)
        // The synced fields go back to the opened ones while the local pick shows.
        assertEquals("dunes" to null, draft.backgroundId to draft.backgroundUrl)
        assertEquals(nadia, draft.toProfile())
        assertTrue(draft.hasChanges)
        draft.toggleLocalBackground(ruby)
        assertEquals(ProfileBackgroundPick.Local(ruby), draft.backgroundPick)
        draft.toggleLocalBackground(ruby)
        assertNull(draft.localBackground)
        assertEquals(ProfileBackgroundPick.Catalog("dunes"), draft.backgroundPick)
        assertFalse(draft.hasChanges)
        // The website URL is the synced background too.
        val custom = ProfileEditorDraft.edit(sara, true, ratingCap = null, look = null, sets = BundledLookSets)
        custom.toggleLocalBackground(ember)
        custom.toggleLocalBackground(ember)
        assertEquals(ProfileBackgroundPick.Custom, custom.backgroundPick)
        assertEquals(sara, custom.toProfile())
    }

    @Test
    fun `normal, catalog and custom picked from a local background replace it`() {
        val fromNormal = ProfileEditorDraft.edit(nadia, true, ratingCap = null, look = null, sets = BundledLookSets).apply { toggleLocalBackground(ember) }
        fromNormal.selectStandardBackground()
        assertEquals(ProfileBackgroundPick.Standard, fromNormal.backgroundPick)
        assertNull(fromNormal.localBackground)
        assertEquals(nadia.copy(profileBackgroundId = null), fromNormal.toProfile())

        // The opened catalog tile, picked over a local pick, selects it (not a toggle-off).
        val fromCatalog = ProfileEditorDraft.edit(nadia, true, ratingCap = null, look = null, sets = BundledLookSets).apply { toggleLocalBackground(ember) }
        fromCatalog.toggleCatalogBackground("dunes")
        assertEquals(ProfileBackgroundPick.Catalog("dunes"), fromCatalog.backgroundPick)
        assertNull(fromCatalog.localBackground)

        val fromCustom = ProfileEditorDraft.edit(sara, true, ratingCap = null, look = null, sets = BundledLookSets).apply { toggleLocalBackground(ember) }
        fromCustom.toggleCustomBackground()
        assertEquals(ProfileBackgroundPick.Custom, fromCustom.backgroundPick)
        assertNull(fromCustom.localBackground)
        assertEquals(sara, fromCustom.toProfile())
    }

    @Test
    fun `a bundled background saves the synced fields as opened and keeps the look`() {
        val draft = ProfileEditorDraft.edit(nadia, true, ratingCap = null, sets = BundledLookSets, look = null)
        draft.toggleLocalBackground(ember)
        val saved = draft.toProfile()
        assertEquals(nadia, saved)
        val look = ProfileLookEntry.of(saved, draft.localAvatar, draft.localBackground)
        assertEquals(ProfileLookEntry(background = ProfileLookChoice(ember, "Nadia", "dunes")), look)
        // Reopened from that entry, the bundled pick is selected again.
        val reopened = ProfileEditorDraft.edit(saved, true, ratingCap = null, look, BundledLookSets)
        assertEquals(ProfileBackgroundPick.Local(ember), reopened.backgroundPick)
        assertFalse(reopened.hasChanges)
        // Deselected, it saves the synced background and clears the entry.
        reopened.toggleLocalBackground(ember)
        assertTrue(reopened.hasChanges)
        assertEquals(ProfileBackgroundPick.Catalog("dunes"), reopened.backgroundPick)
        assertEquals(nadia, reopened.toProfile())
        assertNull(ProfileLookEntry.of(reopened.toProfile(), reopened.localAvatar, reopened.localBackground))
    }

    @Test
    fun `the field previews the focused background, else the selection`() {
        val draft = ProfileEditorDraft.edit(sara, true, ratingCap = null, look = null, sets = BundledLookSets)
        assertEquals(ProfileBackgroundSelection.Custom("https://img/sara-bg.png"), draft.previewBackground)
        draft.focusBackground(ProfileBackgroundPick.Local(ember))
        assertEquals(ProfileBackgroundSelection.Local(ember), draft.previewBackground)
        draft.focusBackground(ProfileBackgroundPick.Catalog("dunes"))
        assertEquals(ProfileBackgroundSelection.Catalog("dunes"), draft.previewBackground)
        // Normal is the colour field.
        draft.focusBackground(ProfileBackgroundPick.Standard)
        assertNull(draft.previewBackground)
        // Focus gone: the selection again, which a pick changes; focus alone changes nothing to save.
        draft.focusBackground(null)
        draft.toggleLocalBackground(ruby)
        assertEquals(ProfileBackgroundSelection.Local(ruby), draft.previewBackground)
        draft.toggleLocalBackground(ruby)
        draft.focusBackground(ProfileBackgroundPick.Local(ember))
        assertFalse(draft.hasChanges)
    }

    // Spec 44c: My photos.

    private val avatarPhoto = MyPhoto("p1", MyPhotoKind.Avatar, Instant.fromEpochMilliseconds(1), File("p1.jpg"))
    private val bgPhoto = ProfileLookPick.Photo("b1")

    @Test
    fun `a photo tile toggles like a creature and is drawn from the library`() {
        val draft = ProfileEditorDraft.edit(omar, true, ratingCap = null, sets = sets, look = null)
        draft.toggleTile(AvatarTile.Photo(avatarPhoto, "1 Jan"))
        assertEquals(EditorAvatarPick.Local(photo), draft.avatarPick)
        assertEquals(AvatarTile.photoKey("p1"), draft.selectedTileKey)
        assertEquals(AvatarArt.Photo(avatarPhoto), draft.selectedArt(catalog, LocalLookLibrary(listOf(avatarPhoto))) { null })
        // Not in the library (removed meanwhile): no art, the initial shows.
        assertNull(draft.selectedArt(catalog, LocalLookLibrary.Bundled) { null })
        assertEquals(omar, draft.toProfile())
        draft.toggleTile(AvatarTile.Photo(avatarPhoto, "1 Jan"))
        assertEquals(EditorAvatarPick.Synced, draft.avatarPick)
    }

    @Test
    fun `use selects a photo and never toggles it off`() {
        val draft = ProfileEditorDraft.edit(nadia, true, ratingCap = null, look = null, sets = BundledLookSets)
        draft.useLocalAvatar(photo)
        draft.useLocalAvatar(photo)
        assertEquals(EditorAvatarPick.Local(photo), draft.avatarPick)
        draft.useLocalBackground(bgPhoto)
        draft.useLocalBackground(bgPhoto)
        assertEquals(ProfileBackgroundPick.Local(bgPhoto), draft.backgroundPick)
        assertEquals(nadia, draft.toProfile())
    }

    @Test
    fun `removing the draft's background photo returns to the synced background`() {
        val draft = ProfileEditorDraft.edit(nadia, true, ratingCap = null, look = null, sets = BundledLookSets)
        draft.toggleLocalBackground(bgPhoto)
        draft.onPhotoRemoved("p1")
        assertEquals(ProfileBackgroundPick.Local(bgPhoto), draft.backgroundPick)
        draft.onPhotoRemoved("b1")
        assertEquals(ProfileBackgroundPick.Catalog("dunes"), draft.backgroundPick)
        assertFalse(draft.hasChanges)
    }

    @Test
    fun `removing the photo a draft opened with returns to synced and the save clears the entry`() {
        val photoSets = sets.copy(photoIds = setOf("p1", "b1"))
        val look = ProfileLookEntry.of(nadia, photo, bgPhoto)
        val draft = ProfileEditorDraft.edit(nadia, true, ratingCap = null, look, photoSets)
        assertEquals(EditorAvatarPick.Local(photo), draft.avatarPick)
        assertEquals(ProfileBackgroundPick.Local(bgPhoto), draft.backgroundPick)
        draft.onPhotoRemoved("p1")
        draft.onPhotoRemoved("b1")
        assertEquals(EditorAvatarPick.Synced, draft.avatarPick)
        assertEquals(ProfileBackgroundPick.Catalog("dunes"), draft.backgroundPick)
        // A change (Back asks), and saving it drops the entry that named the removed photos.
        assertTrue(draft.hasChanges)
        assertEquals(nadia, draft.toProfile())
        assertNull(ProfileLookEntry.of(draft.toProfile(), draft.localAvatar, draft.localBackground))
    }

    @Test
    fun `the selected background tile follows the resolver's order`() {
        assertEquals(ProfileBackgroundPick.Standard, ProfileEditorDraft.edit(nadia.copy(profileBackgroundId = null), true, ratingCap = null, look = null, sets = BundledLookSets).backgroundPick)
        // A pulled blank id is none, and stays unchanged on save.
        val blank = ProfileEditorDraft.edit(nadia.copy(profileBackgroundId = " "), true, ratingCap = null, look = null, sets = BundledLookSets)
        assertEquals(ProfileBackgroundPick.Standard, blank.backgroundPick)
        assertFalse(blank.hasChanges)
        // A synced id and URL: the URL shows, as on Who's watching.
        assertEquals(ProfileBackgroundPick.Custom, ProfileEditorDraft.edit(sara.copy(profileBackgroundId = "dunes"), true, ratingCap = null, look = null, sets = BundledLookSets).backgroundPick)
    }

    @Test
    fun `with a synced id and url the custom tile toggles and a local pick returns to it`() {
        val both = sara.copy(profileBackgroundId = "dunes")
        val draft = ProfileEditorDraft.edit(both, true, ratingCap = null, look = null, sets = BundledLookSets)
        draft.toggleLocalBackground(ember)
        draft.toggleLocalBackground(ember)
        assertEquals(ProfileBackgroundPick.Custom, draft.backgroundPick)
        assertEquals(both, draft.toProfile())
        assertFalse(draft.hasChanges)
        // The selected Custom pressed again is Normal.
        draft.toggleCustomBackground()
        assertEquals(ProfileBackgroundPick.Standard, draft.backgroundPick)
        assertEquals(null to null, draft.backgroundId to draft.backgroundUrl)
        // Catalog "dunes" is not the selected tile there: pressing it selects it.
        val catalogPress = ProfileEditorDraft.edit(both, true, ratingCap = null, look = null, sets = BundledLookSets)
        catalogPress.toggleCatalogBackground("dunes")
        assertEquals(ProfileBackgroundPick.Catalog("dunes"), catalogPress.backgroundPick)
    }
}
