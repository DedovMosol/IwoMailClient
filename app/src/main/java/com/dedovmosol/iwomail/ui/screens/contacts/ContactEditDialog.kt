package com.dedovmosol.iwomail.ui.screens.contacts

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dedovmosol.iwomail.data.database.ContactEntity
import com.dedovmosol.iwomail.ui.Strings
import com.dedovmosol.iwomail.ui.components.ScrollColumnScrollbar
import com.dedovmosol.iwomail.ui.theme.ScaledAlertDialog
import com.dedovmosol.iwomail.ui.theme.ThemeOutlinedButton
import kotlinx.coroutines.delay

@Composable
internal fun ContactEditDialog(
    contact: ContactEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String, String, String, String, String) -> Unit
) {
    var displayName by rememberSaveable { mutableStateOf(contact?.displayName ?: "") }
    var email by rememberSaveable { mutableStateOf(contact?.email ?: "") }
    var firstName by rememberSaveable { mutableStateOf(contact?.firstName ?: "") }
    var lastName by rememberSaveable { mutableStateOf(contact?.lastName ?: "") }
    var phone by rememberSaveable { mutableStateOf(contact?.phone ?: "") }
    var mobilePhone by rememberSaveable { mutableStateOf(contact?.mobilePhone ?: "") }
    var workPhone by rememberSaveable { mutableStateOf(contact?.workPhone ?: "") }
    var company by rememberSaveable { mutableStateOf(contact?.company ?: "") }
    var department by rememberSaveable { mutableStateOf(contact?.department ?: "") }
    var jobTitle by rememberSaveable { mutableStateOf(contact?.jobTitle ?: "") }
    var notes by rememberSaveable { mutableStateOf(contact?.notes ?: "") }

    // Сохранение фокуса при повороте экрана
    var focusedFieldIndex by rememberSaveable { mutableIntStateOf(-1) }
    val displayNameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val firstNameFocus = remember { FocusRequester() }
    val lastNameFocus = remember { FocusRequester() }
    val phoneFocus = remember { FocusRequester() }
    val mobilePhoneFocus = remember { FocusRequester() }
    val workPhoneFocus = remember { FocusRequester() }
    val companyFocus = remember { FocusRequester() }
    val departmentFocus = remember { FocusRequester() }
    val jobTitleFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }

    // Восстановление фокуса после поворота
    LaunchedEffect(focusedFieldIndex) {
        if (focusedFieldIndex >= 0) {
            delay(100)
            when (focusedFieldIndex) {
                0 -> displayNameFocus.requestFocus()
                1 -> emailFocus.requestFocus()
                2 -> firstNameFocus.requestFocus()
                3 -> lastNameFocus.requestFocus()
                4 -> phoneFocus.requestFocus()
                5 -> mobilePhoneFocus.requestFocus()
                6 -> workPhoneFocus.requestFocus()
                7 -> companyFocus.requestFocus()
                8 -> departmentFocus.requestFocus()
                9 -> jobTitleFocus.requestFocus()
                10 -> notesFocus.requestFocus()
            }
        }
    }

    val currentConfig = LocalContext.current.resources.configuration
    val isLandscape = currentConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
    val screenHeightDp = currentConfig.screenHeightDp
    // Высота текстовой области: оставляем место для заголовка и кнопок
    // Оставляем место для заголовка (~56dp) и кнопок (~56dp) внутри Surface(maxHeight=500dp)
    val maxTextHeight = if (isLandscape) {
        (screenHeightDp * 0.40f).dp.coerceIn(150.dp, 280.dp)
    } else {
        (screenHeightDp * 0.50f).dp.coerceIn(200.dp, 340.dp)
    }

    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        scrollable = false, // скроллим только текстовую область, кнопки всегда видны
        title = { Text(if (contact == null) Strings.addContact else Strings.editContact) },
        text = {
            val scrollState = rememberScrollState()

            Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxTextHeight)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = if (scrollState.maxValue > 0) 10.dp else 0.dp)
                ) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text(Strings.displayName) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(displayNameFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 0 }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = firstName,
                            onValueChange = { firstName = it },
                            label = { Text(Strings.firstName) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(firstNameFocus)
                                .onFocusChanged { if (it.isFocused) focusedFieldIndex = 2 }
                        )
                        OutlinedTextField(
                            value = lastName,
                            onValueChange = { lastName = it },
                            label = { Text(Strings.lastName) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(lastNameFocus)
                                .onFocusChanged { if (it.isFocused) focusedFieldIndex = 3 }
                        )
                    }
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(Strings.emailAddress) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(emailFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 1 }
                    )
                    // Телефоны в ряд
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(Strings.phone) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(phoneFocus)
                                .onFocusChanged { if (it.isFocused) focusedFieldIndex = 4 }
                        )
                        OutlinedTextField(
                            value = mobilePhone,
                            onValueChange = { mobilePhone = it },
                            label = { Text(Strings.mobilePhone) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(mobilePhoneFocus)
                                .onFocusChanged { if (it.isFocused) focusedFieldIndex = 5 }
                        )
                    }
                    OutlinedTextField(
                        value = workPhone,
                        onValueChange = { workPhone = it },
                        label = { Text(Strings.workPhone) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(workPhoneFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 6 }
                    )
                    // Компания и отдел в ряд
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = company,
                            onValueChange = { company = it },
                            label = { Text(Strings.company) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(companyFocus)
                                .onFocusChanged { if (it.isFocused) focusedFieldIndex = 7 }
                        )
                        OutlinedTextField(
                            value = department,
                            onValueChange = { department = it },
                            label = { Text(Strings.department) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(departmentFocus)
                                .onFocusChanged { if (it.isFocused) focusedFieldIndex = 8 }
                        )
                    }
                    OutlinedTextField(
                        value = jobTitle,
                        onValueChange = { jobTitle = it },
                        label = { Text(Strings.jobTitle) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(jobTitleFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 9 }
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(Strings.contactNotes) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(notesFocus)
                            .onFocusChanged { if (it.isFocused) focusedFieldIndex = 10 }
                    )
                }

                ScrollColumnScrollbar(scrollState)
            }
        },
        confirmButton = {
            ThemeOutlinedButton(
                onClick = {
                    val name = displayName.ifBlank { "$firstName $lastName".trim().ifBlank { email } }
                    onSave(name, email, firstName, lastName, phone, mobilePhone, workPhone, company, department, jobTitle, notes)
                },
                text = Strings.save,
                enabled = displayName.isNotBlank() || email.isNotBlank() || firstName.isNotBlank() || lastName.isNotBlank()
            )
        },
        dismissButton = {
            ThemeOutlinedButton(
                onClick = onDismiss,
                text = Strings.cancel
            )
        }
    )
}
