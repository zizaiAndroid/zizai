/*
 * Copyright (C) 2026 Zizai contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.zizai.ai

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes
import java.text.DateFormat
import java.time.Period
import java.util.Date
import java.util.TimeZone

/**
 * Settings → Zizai Pro: plan, this month's use, the subscription, and the device account (ADR-9).
 * Everything shown comes from the server's last answer; nothing here decides on its own who is Pro.
 */
@Composable
fun ZizaiProScreen() = FlorisScreen {
    title = stringRes(R.string.zizai__pro__title)

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val status by ZizaiPro.status.collectAsState()
        var shop by remember { mutableStateOf<ZizaiPro.Shop?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var usingZizai by remember { mutableStateOf(ZizaiAi.isSelectedForAiCards()) }

        LaunchedEffect(Unit) {
            error = runCatching { ZizaiPro.refresh() }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            runCatching { ZizaiPro.restore(context) }
            shop = runCatching { ZizaiPro.shop(context) }.getOrElse { ZizaiPro.Shop.Unavailable(-1) }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val current = status
            Text(
                text = when {
                    current == null -> stringRes(R.string.zizai__pro__loading)
                    current.pro -> stringRes(R.string.zizai__pro__plan_pro, "date" to formatDate(current.proUntil))
                    else -> stringRes(R.string.zizai__pro__plan_free)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (current != null) {
                Text(
                    text = if (current.pro) {
                        stringRes(R.string.zizai__pro__usage_pro, "used" to current.actions.toString())
                    } else {
                        stringRes(
                            R.string.zizai__pro__usage_free,
                            "used" to minOf(current.actions, current.actionsLimit).toString(),
                            "limit" to current.actionsLimit.toString(),
                            "date" to formatDate(current.resetsAt, utc = true),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (current.slowed) Text(stringRes(R.string.zizai__pro__slowed), color = MaterialTheme.colorScheme.error)
            }
            error?.let { Text(stringRes(R.string.zizai__pro__error, "message" to it), color = MaterialTheme.colorScheme.error) }

            if (current?.pro != true) {
                Text(stringRes(R.string.zizai__pro__benefits), style = MaterialTheme.typography.bodyMedium)
                when (val s = shop) {
                    null -> Text(stringRes(R.string.zizai__pro__loading))
                    is ZizaiPro.Shop.Unavailable -> Text(stringRes(R.string.zizai__pro__unavailable))
                    is ZizaiPro.Shop.Ready -> s.plans.forEachIndexed { index, plan ->
                        val label = planLabel(plan)
                        val onClick: () -> Unit = {
                            val activity = context.findActivity()
                            if (activity != null && !busy) {
                                busy = true
                                scope.launch {
                                    when (val result = ZizaiPro.purchase(activity, plan)) {
                                        is ZizaiPro.PurchaseResult.Done -> toast(context, R.string.zizai__pro__thanks)
                                        ZizaiPro.PurchaseResult.Pending -> toast(context, R.string.zizai__pro__pending)
                                        is ZizaiPro.PurchaseResult.Failed -> error = result.message
                                        ZizaiPro.PurchaseResult.Cancelled -> Unit
                                    }
                                    busy = false
                                }
                            }
                        }
                        if (index == 0) {
                            Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        } else {
                            OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        }
                    }
                }
            }
        }

        if (!usingZizai) {
            Preference(
                icon = Icons.Default.AutoAwesome,
                title = stringRes(R.string.zizai__pro__use_for_cards),
                summary = stringRes(R.string.zizai__pro__use_for_cards_summary, "limit" to (status?.actionsLimit ?: 30).toString()),
                onClick = {
                    scope.launch {
                        ZizaiAi.useForAiCards()
                        usingZizai = true
                    }
                },
            )
        } else {
            Preference(icon = Icons.Default.AutoAwesome, title = stringRes(R.string.zizai__pro__in_use))
        }
        Preference(
            title = stringRes(R.string.zizai__pro__restore),
            onClick = {
                scope.launch {
                    busy = true
                    runCatching { ZizaiPro.restore(context) }
                    error = runCatching { ZizaiPro.refresh() }.exceptionOrNull()?.message
                    busy = false
                }
            },
        )
        Preference(
            title = stringRes(R.string.zizai__pro__manage),
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/account/subscriptions?sku=${ZizaiPro.PRODUCT_ID}&package=${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
        Text(
            text = stringRes(R.string.zizai__pro__privacy),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        status?.let { s ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringRes(R.string.zizai__pro__account_id), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(s.accountId, style = MaterialTheme.typography.bodySmall) }
            }
            Preference(
                title = stringRes(R.string.zizai__pro__delete),
                onClick = { confirmDelete = true },
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringRes(R.string.zizai__pro__delete)) },
                text = { Text(stringRes(R.string.zizai__pro__delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            runCatching { ZizaiPro.deleteAccount() }
                                .onSuccess { toast(context, R.string.zizai__pro__deleted) }
                                .onFailure { error = it.message }
                        }
                    }) { Text(stringRes(R.string.zizai__pro__delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringRes(android.R.string.cancel)) }
                },
            )
        }
    }
}

/** The Zizai AI row on the providers screen: opens this screen instead of a key editor. */
@Composable
fun ZizaiAiProviderRow(trailing: (@Composable () -> Unit)?) {
    val navController = LocalNavController.current
    val status by ZizaiPro.status.collectAsState()
    Preference(
        icon = Icons.Default.AutoAwesome,
        title = ZizaiAi.preset.displayName,
        summary = stringRes(
            if (status?.pro == true) R.string.zizai__pro__plan_pro_short else R.string.zizai__pro__provider_summary,
            "limit" to (status?.actionsLimit ?: 30).toString(),
        ),
        trailing = trailing,
        onClick = { navController.navigate(dev.patrickgold.florisboard.app.Routes.Settings.ZizaiPro) },
    )
}

@Composable
private fun planLabel(plan: ZizaiPro.Plan): String {
    val price = if (plan.basePlanId == ZizaiPro.BASE_PLAN_YEARLY || plan.billingPeriod == "P1Y") {
        stringRes(R.string.zizai__pro__yearly, "price" to plan.formattedPrice)
    } else {
        stringRes(R.string.zizai__pro__monthly, "price" to plan.formattedPrice)
    }
    val days = plan.freeTrial?.let { runCatching { Period.parse(it).days + Period.parse(it).months * 30 }.getOrNull() }
    return if (days != null && days > 0) "$price · ${stringRes(R.string.zizai__pro__trial, "days" to days.toString())}" else price
}

/** [utc] for the monthly reset, which is midnight UTC on the 1st and would show as the 30th or 31st west of Greenwich. */
private fun formatDate(epochMs: Long, utc: Boolean = false): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).apply { if (utc) timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))

private fun toast(context: Context, res: Int) = Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
