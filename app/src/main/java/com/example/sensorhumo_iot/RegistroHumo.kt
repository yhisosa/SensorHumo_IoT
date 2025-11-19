package com.example.sensorhumo_iot // Asegúrate que este sea tu nombre de paquete

/**
 * Esta es una Data Class (Modelo de Datos) que representa un solo registro
 * que se guardará en Firebase Realtime Database.
 * Cumple con el requisito de almacenamiento externo (Paso 5).
 */
data class RegistroHumo(
    // Identificador único y registro de tiempo
    val timestamp: Long = System.currentTimeMillis(),

    // La lectura del sensor de humo, ya DESCIFRADA (Paso 6)
    val lecturaHumo: Int = 0,

    // Un texto que describe el evento (Paso 3 y 4)
    val evento: String = "", // Ej: "LECTURA_NORMAL", "ALARMA_ACTIVADA", "CONTROL_RELAY_ON"

    // Estado para el manejo de almacenamiento local (Paso 5)
    val estado: String = "ONLINE" // "ONLINE" o "OFFLINE" (para sincronización)
)