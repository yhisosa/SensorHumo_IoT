package com.example.sensorhumo_iot

import android.content.Context
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import android.util.Log

/**
 * Esta clase maneja la comunicación TCP/IP con el servidor ESP32.
 * Implementa la seguridad (Paso 6) y la lógica de flujo de datos (Paso 3 y 4).
 */
class ComunicacionTCP(private val context: Context) {

    // --- CONSTANTES DE SEGURIDAD (DEBEN COINCIDIR CON EL ESP32) ---
    private val CLAVE_SEGURIDAD = "CLAVE_SEGURA_TI3042"
    private val CLAVE_CIFRADO = 0xAA // Constante para el cifrado XOR

    // --- VARIABLES DE RED ---
    private var socket: Socket? = null
    private var output: PrintWriter? = null
    private var input: BufferedReader? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO)

    // Referencia al MainActivity para actualizar la UI, Firebase y Alertas
    private val mainActivity = context as MainActivity

    /**
     * Función que revierte el cifrado aplicado por el ESP32 (Paso 6).
     */
    private fun descifrarDato(datoCifrado: Int): Int {
        return datoCifrado xor CLAVE_CIFRADO
    }

    /**
     * Inicia la conexión, realiza la autenticación y comienza a escuchar.
     */
    fun conectar(ip: String, puerto: Int) {
        scope.launch {
            try {
                if (isConnected) return@launch

                socket = Socket(ip, puerto)
                output = PrintWriter(socket!!.getOutputStream(), true)
                input = BufferedReader(InputStreamReader(socket!!.getInputStream()))

                // 1. AUTENTICACIÓN (Paso 6)
                output!!.println(CLAVE_SEGURIDAD)

                // 2. Leer la confirmación del ESP32
                val confirmacion = input!!.readLine()
                if (confirmacion == "OK_AUTH") {
                    isConnected = true
                    withContext(Dispatchers.Main) {
                        mainActivity.actualizarEstadoConexion("Estado: CONECTADO y AUTENTICADO ✅")
                    }
                    iniciarLectura()
                } else {
                    handleDisconnection("FALLO AUTENTICACIÓN")
                }
            } catch (e: Exception) {
                handleDisconnection("Error de conexión: ${e.message}")
            }
        }
    }

    /**
     * Lee continuamente los datos del socket (Paso 3, 5 y 6).
     */
    private fun iniciarLectura() {
        scope.launch {
            try {
                while (isConnected) {
                    val linea = input?.readLine() ?: break

                    if (linea.startsWith("GAS:")) {
                        val datoCifrado = linea.substringAfter("GAS:").trim().toIntOrNull() ?: 0
                        val datoReal = descifrarDato(datoCifrado)
                        withContext(Dispatchers.Main) {
                            mainActivity.actualizarUIyGuardar(datoReal)
                        }
                    }
                    // --- MANEJO DE ALERTAS (CORREGIDO) ---
                    else if (linea.startsWith("ALERTA:HUMO")) {
                        withContext(Dispatchers.Main) {
                            // Ahora usa la función correcta con el mensaje de alerta
                            mainActivity.mostrarAlerta("🚨 ALARMA DE HUMO DETECTADA!")
                        }
                    }
                    else if (linea.startsWith("ALERTA:RUIDO")) {
                        withContext(Dispatchers.Main) {
                            // Alerta para el nuevo sensor de sonido (si lo activas)
                            mainActivity.mostrarAlerta("📢 RUIDO FUERTE DETECTADO!")
                        }
                    }
                    // ------------------------------------
                }
            } catch (e: Exception) {
                handleDisconnection("Pérdida de comunicación: ${e.message}")
            }
        }
    }

    /**
     * Envía comandos al ESP32 (Paso 4).
     */
    fun enviarComando(comando: String) {
        scope.launch {
            if (isConnected && output != null) {
                output!!.println(comando)
            } else {
                withContext(Dispatchers.Main) {
                    mainActivity.actualizarEstadoConexion("Error: Desconectado. No se envió comando.")
                }
            }
        }
    }

    /**
     * Maneja la desconexión, limpia el estado y notifica a la App (Paso 5).
     */
    private fun handleDisconnection(mensajeError: String) {
        isConnected = false
        socket?.close()
        socket = null
        output = null
        input = null

        scope.launch(Dispatchers.Main) {
            mainActivity.actualizarEstadoConexion("Estado: DESCONECTADO ($mensajeError)")
            mainActivity.activarModoOffline()
        }
    }
}