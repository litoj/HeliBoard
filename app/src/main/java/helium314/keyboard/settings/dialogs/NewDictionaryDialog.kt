// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.compat.locale
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.dictionary.ReadOnlyBinaryDictionary
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.latin.makedict.DictionaryHeader
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.ScriptUtils.script
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.settings.DropDownField
import helium314.keyboard.settings.WithSmallTitle
import java.io.File
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.window.Dialog
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.makedict.CombinedInputOutput
import helium314.keyboard.latin.makedict.DictIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NewDictionaryDialog(
    onDismissRequest: () -> Unit,
    cachedFile: File,
    mainLocale: Locale?
) {
    // converting a combined dictionary can take seconds, keep it off the UI thread
    var result by remember { mutableStateOf<Pair<Int?, DictionaryHeader?>?>(null) }
    LaunchedEffect(cachedFile) {
        result = withContext(Dispatchers.IO) { checkDict(cachedFile) }
    }
    val checked = result
    if (checked == null) {
        Dialog(onDismissRequest = { }) {
            Surface(shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.dictionary_loading), Modifier.padding(24.dp))
            }
        }
        return
    }
    val (error, header) = checked
    if (error != null) {
        InfoDialog(stringResource(error), onDismissRequest)
        cachedFile.delete()
    } else if (header != null) {
        val ctx = LocalContext.current
        val dictLocale = header.mLocaleString.constructLocale()
        val enabledLocales = SubtypeSettings.getEnabledSubtypes().map { it.locale() }
        val enabledLanguages = enabledLocales.map { it.language }
        val comparer = compareBy<Locale>(
            { it != mainLocale },
            { it !in enabledLocales },
            { it != dictLocale },
            { it.language !in enabledLanguages },
            { it.script() != dictLocale.script() }
        )
        val locales = SubtypeSettings.getAvailableSubtypeLocales()
            .filter { it.script() == dictLocale.script() || it.script() == mainLocale?.script() }
            .sortedWith(comparer)
        var locale by remember {
            mutableStateOf(mainLocale
                ?: RichInputMethodManager.getInstance().findSubtypeForHintLocale(dictLocale)?.locale()
                ?: dictLocale.takeIf { it in locales }
                ?: locales.first())
        }
        val cacheDir = DictionaryInfoUtils.getCacheDirectoryForLocale(locale, ctx)
        val dictFile = File(cacheDir, header.mIdString.substringBefore(":") + "_" + DictionaryInfoUtils.USER_DICTIONARY_SUFFIX)
        val type = header.mIdString.substringBefore(":")
        val info = header.info(LocalConfiguration.current.locale())
        ThreeButtonAlertDialog(
            onDismissRequest = { onDismissRequest(); cachedFile.delete() },
            onConfirmed = {
                dictFile.parentFile?.mkdirs()
                dictFile.delete()
                cachedFile.renameTo(dictFile)
                if (type == Dictionary.TYPE_MAIN) {
                    // replaced main dict, remove the one created from internal data
                    val internalMainDictFile = File(cacheDir, DictionaryInfoUtils.MAIN_DICT_FILE_NAME)
                    internalMainDictFile.delete()
                }
                val newDictBroadcast = Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)
                ctx.sendBroadcast(newDictBroadcast)
            },
            confirmButtonText = stringResource(if (dictFile.exists()) R.string.replace_dictionary else android.R.string.ok),
            title = { Text(stringResource(R.string.add_new_dictionary_title)) },
            content = {
                Column {
                    Text(info, Modifier.padding(bottom = 10.dp))
                    WithSmallTitle(stringResource(R.string.button_select_language)) {
                        DropDownField(
                            selectedItem = locale,
                            onSelected = { locale = it },
                            items = locales
                        ) { Text(it.localizedDisplayName(LocalResources.current)) }
                    }
                    if (locale.script() != dictLocale.script()) {
                        // whatever, still allow it if the user wants
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.dictionary_file_wrong_script),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
                        )
                    }
                    if (dictFile.exists()) {
                        val oldInfo = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(dictFile)?.info(LocalConfiguration.current.locale())
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.replace_dictionary_message, type, oldInfo ?: "(no info)", info),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            scrollContent = true,
        )
    }
}

private fun checkDict(file: File): Pair<Int?, DictionaryHeader?> {
    convertDictIfNecessary(file)
    val newHeader = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(file)
        ?: return R.string.dictionary_file_error to null

    val locale = newHeader.mLocaleString.constructLocale()
    val dict = ReadOnlyBinaryDictionary(file.absolutePath, 0, file.length(), false, locale, "test")
    if (!dict.isValidDictionary) {
        dict.close()
        return R.string.dictionary_load_error to null
    }
    return null to newHeader
}

private fun convertDictIfNecessary(file: File) {
    if (DictionaryInfoUtils.getDictionaryFileHeaderOrNull(file) != null) return
    val path = file.absolutePath
    try {
        val fusionDict = if (CombinedInputOutput.isCombinedDictionary(path))
            DictIO.readCombinedDictionary(path)
        else runCatching { DictIO.readCompressedCombinedDictionary(path) }.getOrNull() ?:
            DictIO.readXmDictionary(path, null, null)
        file.delete()
        DictIO.writeBinaryDictionary(path, fusionDict)
    } catch (_: Exception) {
    }
}
