package com.steptracker

import java.util.Locale

/**
 * Centralized notification strings for all supported languages.
 *
 * To add a new language, add a single entry to the [translations] map below.
 * No other file needs to change.
 */
data class NotificationStrings(
    val locale: Locale,
    val steps: String,
    val countingSteps: String,
    val origin: String,
    val destination: String,
    val completed: String,
    val arrived: String,
    val yourDestination: String,
    val dailyGoalTitle: String,
    val dailyGoalReached: String,
    val channelBadges: String,
    val channelBadgesDesc: String,
    val newBadgeUnlocked: String,
    val earnedBadge: String,
    val channelBackups: String,
    val backupCreated: String,
    val savedIn: String,
    val driveBackupSaved: String,
) {
    companion object {

        private val translations: Map<String, NotificationStrings> = mapOf(
            "es" to NotificationStrings(
                locale = Locale("es", "ES"),
                steps = "pasos",
                countingSteps = "Contando pasos",
                origin = "Origen",
                destination = "destino",
                completed = "completado",
                arrived = "Has llegado",
                yourDestination = "Tu destino",
                dailyGoalTitle = "¡Objetivo diario!",
                dailyGoalReached = "¡Objetivo diario alcanzado!",
                channelBadges = "Insignias desbloqueadas",
                channelBadgesDesc = "Notificaciones cuando desbloqueas una insignia nueva",
                newBadgeUnlocked = "Nueva insignia desbloqueada",
                earnedBadge = "Has ganado una insignia",
                channelBackups = "Copias de seguridad",
                backupCreated = "Copia de seguridad creada",
                savedIn = "Guardada en: ",
                driveBackupSaved = "Copia de seguridad guardada en Google Drive",
            ),
            "fr" to NotificationStrings(
                locale = Locale("fr", "FR"),
                steps = "pas",
                countingSteps = "Comptage des pas",
                origin = "Origine",
                destination = "destination",
                completed = "complété",
                arrived = "Vous êtes arrivé",
                yourDestination = "Votre destination",
                dailyGoalTitle = "Objectif quotidien !",
                dailyGoalReached = "Objectif quotidien atteint !",
                channelBadges = "Badges débloqués",
                channelBadgesDesc = "Notifications lors du déblocage d'un badge",
                newBadgeUnlocked = "Nouveau badge débloqué",
                earnedBadge = "Vous avez gagné un badge",
                channelBackups = "Sauvegardes",
                backupCreated = "Sauvegarde créée",
                savedIn = "Enregistrée dans : ",
                driveBackupSaved = "Sauvegarde enregistrée sur Google Drive",
            ),
            "pt" to NotificationStrings(
                locale = Locale("pt", "BR"),
                steps = "passos",
                countingSteps = "Contando passos",
                origin = "Origem",
                destination = "destino",
                completed = "concluído",
                arrived = "Você chegou",
                yourDestination = "Seu destino",
                dailyGoalTitle = "Meta diária!",
                dailyGoalReached = "Meta diária alcançada!",
                channelBadges = "Medalhas desbloqueadas",
                channelBadgesDesc = "Notificações quando você desbloqueia uma nova medalha",
                newBadgeUnlocked = "Nova medalha desbloqueada",
                earnedBadge = "Você ganhou uma medalha",
                channelBackups = "Backups",
                backupCreated = "Backup criado",
                savedIn = "Salvo em: ",
                driveBackupSaved = "Backup salvo no Google Drive",
            ),
        )

        private val english = NotificationStrings(
            locale = Locale.US,
            steps = "steps",
            countingSteps = "Counting steps",
            origin = "Origin",
            destination = "destination",
            completed = "completed",
            arrived = "You've arrived",
            yourDestination = "Your destination",
            dailyGoalTitle = "Daily goal!",
            dailyGoalReached = "Daily goal reached!",
            channelBadges = "Unlocked badges",
            channelBadgesDesc = "Notifications when you unlock a new badge",
            newBadgeUnlocked = "New badge unlocked",
            earnedBadge = "You earned a badge",
            channelBackups = "Backups",
            backupCreated = "Backup created",
            savedIn = "Saved in: ",
            driveBackupSaved = "Backup saved to Google Drive",
        )

        /**
         * Returns the [NotificationStrings] for the given language code (e.g. "es", "fr", "pt").
         * Falls back to English for unknown codes.
         * The language string is normalised automatically (lowercased, trimmed to 2 chars).
         */
        fun forLanguage(language: String): NotificationStrings =
            translations[language.lowercase(Locale.ROOT).take(2)] ?: english
    }
}
