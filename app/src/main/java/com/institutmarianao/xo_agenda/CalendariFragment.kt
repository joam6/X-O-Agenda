package com.institutmarianao.xo_agenda

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CalendarView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.institutmarianao.xo_agenda.adapters.CalendarItemAdapter
import com.institutmarianao.xo_agenda.adapters.OnItemActionListener
import com.institutmarianao.xo_agenda.models.CalendarItem
import java.util.Locale

class CalendariFragment : Fragment(), OnItemActionListener {

    lateinit var calendarItems: MutableList<CalendarItem>
    lateinit var adapter: CalendarItemAdapter
    lateinit var recyclerView: androidx.recyclerview.widget.RecyclerView
    var selectedDate: Calendar = Calendar.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        val view = inflater.inflate(R.layout.fragment_calendar, container, false)

        // Botón para abrir el menú lateral
        val btnOpenMenu = view.findViewById<ImageView>(R.id.btnOpenMenu)
        val anadir = view.findViewById<ImageButton>(R.id.btnanadir)

        btnOpenMenu.setOnClickListener {
            // Llama al método público de la actividad para abrir el drawer
            (activity as? MenuActivity)?.openDrawer()
        }
        val calendarView = view.findViewById<CalendarView>(R.id.calendar)
        val txtDay = view.findViewById<TextView>(R.id.txtDay)


        //mostrar tareas y eventos
        selectedDate.time = Calendar.getInstance().time
        calendarItems = mutableListOf()
        adapter = CalendarItemAdapter(calendarItems, this)
        recyclerView = view.findViewById(R.id.recyclerViewCalendarItems)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        cargareventosytareas(calendarItems, adapter, selectedDate)


        val today = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("'Día' EEEE, d 'de' MMMM 'de' yyyy", Locale("es", "ES"))
        val formattedDate = dateFormat.format(today.time)
        txtDay.text =
            formattedDate.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            selectedDate.set(year, month, dayOfMonth, 0, 0, 0)
            selectedDate.set(Calendar.MILLISECOND, 0)
            // Solo permite seleccionar a partir de hoy
            calendarView.minDate = Calendar.getInstance().timeInMillis

            val dateFormat =
                SimpleDateFormat("'Dia' EEEE, d 'de' MMMM 'de' yyyy", Locale("es", "ES"))
            txtDay.text = dateFormat.format(selectedDate.time)
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }

            cargareventosytareas(calendarItems, adapter, selectedDate)


        }


        anadir.setOnClickListener {
            val builder = AlertDialog.Builder(requireContext())
            builder.setTitle("Selecciona una opció")

            val options = arrayOf("Tasca", "Esdeveniment")

            builder.setSingleChoiceItems(options, -1) { dialog, which ->
                dialog.dismiss() // Cierra el primer diálogo

                when (which) {
                    0 -> { // Tasca
                        mostrarDialogAfegirTasca()
                    }

                    1 -> { // Esdeveniments
                        mostrarDialogAfegirEvent()
                    }
                }
            }

            builder.setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }

            builder.show()
        }
        return view
    }

    private fun cargareventosytareas(
        calendarItems: MutableList<CalendarItem>,
        adapter: CalendarItemAdapter,
        filterDate: Calendar
    ) {
        val db = FirebaseFirestore.getInstance()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        // Calcula el inicio y fin del día seleccionado
        val startOfDay = filterDate.clone() as Calendar
        startOfDay.set(Calendar.HOUR_OF_DAY, 0)
        startOfDay.set(Calendar.MINUTE, 0)
        startOfDay.set(Calendar.SECOND, 0)
        startOfDay.set(Calendar.MILLISECOND, 0)

        val endOfDay = filterDate.clone() as Calendar
        endOfDay.set(Calendar.HOUR_OF_DAY, 23)
        endOfDay.set(Calendar.MINUTE, 59)
        endOfDay.set(Calendar.SECOND, 59)
        endOfDay.set(Calendar.MILLISECOND, 999)

        calendarItems.clear()

        // Función para procesar los documentos ya filtrados
        fun procesarDocumentos(docs: QuerySnapshot, tipo: String) {
            val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES"))
            for (doc in docs) {
                val id = doc.id
                val title = doc.getString("titol") ?: ""
                val description = doc.getString("descripció") ?: ""

                // Dependiendo de si es tarea o evento, tomamos data_limit o data_inici
                val tsDate = if (tipo == "Tasca") {
                    doc.getTimestamp("data_limit")
                } else {
                    doc.getTimestamp("data_inici")
                } ?: continue

                val itemDate = tsDate.toDate()
                // Sólo añadimos si cae en el rango del día seleccionado
                if (itemDate.before(startOfDay.time) || itemDate.after(endOfDay.time)) {
                    continue
                }

                val dateTime = if (tipo == "Tasca") {
                    fmt.format(itemDate)
                } else {
                    // Para eventos concatenamos inicio y fin
                    val tsEnd = doc.getTimestamp("data_fi") ?: tsDate
                    "${fmt.format(itemDate)} - ${fmt.format(tsEnd.toDate())}"
                }

                calendarItems.add(
                    CalendarItem(id, title, description, dateTime, tipo, itemDate)
                )
            }
            calendarItems.sortBy { it.fechaOrdenacion }
            adapter.notifyDataSetChanged()
        }

        // Hacemos dos consultas separadas (una para tareas, otra para eventos)
        db.collection("usuarios").document(uid).collection("tasques")
            // Firestore permite consultas por rango sobre timestamp:
            .whereGreaterThanOrEqualTo("data_limit", startOfDay.time)
            .whereLessThanOrEqualTo("data_limit", endOfDay.time)
            .get()
            .addOnSuccessListener { procesarDocumentos(it, "Tasca") }

        db.collection("usuarios").document(uid).collection("esdeveniments")
            .whereGreaterThanOrEqualTo("data_inici", startOfDay.time)
            .whereLessThanOrEqualTo("data_inici", endOfDay.time)
            .get()
            .addOnSuccessListener {
                procesarDocumentos(it, "Esdeveniment")
            }
    }

    @SuppressLint("MissingInflatedId")
    fun mostrarDialogAfegirTasca() {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_afegir_tasca, null)

        val editTextTitol = dialogView.findViewById<EditText>(R.id.editTextTitol)
        val editTextDescripcio = dialogView.findViewById<EditText>(R.id.editTextDescripcio)
        val textViewDataLimit = dialogView.findViewById<TextView>(R.id.textViewDataLimit)
        val textViewRecordatori = dialogView.findViewById<TextView>(R.id.textviewRecordatori)
        val buttonGuardar = dialogView.findViewById<Button>(R.id.buttonGuardarTasca)

        val spinnerEstat = dialogView.findViewById<Spinner>(R.id.spinnerEstat)
        spinnerEstat.visibility = View.GONE
        val labelEstat = dialogView.findViewById<TextView>(R.id.textviewEstado)
        labelEstat.visibility = View.GONE


        var dataLimitSeleccionada: Timestamp? = null
        var recordatoriSeleccionat: Timestamp? = null
        val calendar = Calendar.getInstance()

        // Selección de data límit
        textViewDataLimit.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            textViewDataLimit.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            dataLimitSeleccionada = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.datePicker.minDate = Calendar.getInstance().timeInMillis
            datePicker.show()
        }

        // Selección de recordatori
        textViewRecordatori.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            textViewRecordatori.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            recordatoriSeleccionat = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.show()
        }

        val dialog = builder.setView(dialogView)
            .setTitle("Nova Tasca")
            .create()

        buttonGuardar.setOnClickListener {
            val titol = editTextTitol.text.toString().trim()
            val descripcio = editTextDescripcio.text.toString().trim()
            val uid = FirebaseAuth.getInstance().currentUser?.uid

            if (titol.isEmpty() || dataLimitSeleccionada == null) {
                Toast.makeText(
                    requireContext(),
                    "Omple el títol i la data límit",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (recordatoriSeleccionat != null && recordatoriSeleccionat!! > dataLimitSeleccionada!!) {
                Toast.makeText(
                    requireContext(),
                    "El recordatori no pot ser després de la data límit",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (uid != null) {
                val db = FirebaseFirestore.getInstance()
                val tasca = hashMapOf(
                    "titol" to titol,
                    "descripció" to descripcio,
                    "data_limit" to dataLimitSeleccionada,
                    "recordatori" to recordatoriSeleccionat
                )

                db.collection("usuarios")
                    .document(uid)
                    .collection("tasques")
                    .add(tasca)
                    .addOnSuccessListener { docRef ->
                        Toast.makeText(requireContext(), "Tasca guardada", Toast.LENGTH_SHORT)
                            .show()
                        dialog.dismiss()
                        cargareventosytareas(calendarItems, adapter, selectedDate)
                        recordatoriSeleccionat?.let { ts ->
                            val am =
                                requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager

                            // 1) Crea el Intent y el PendingIntent (pi)
                            val reminderIntent =
                                Intent(requireContext(), ReminderReceiver::class.java).apply {
                                    putExtra("docId", docRef.id)
                                    putExtra("type", "tasques")
                                    putExtra("titol", titol)
                                    putExtra("descripcio", descripcio)
                                }
                            val requestCode = docRef.id.hashCode()
                            val pi = PendingIntent.getBroadcast(
                                requireContext(),
                                requestCode,
                                reminderIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )

                            // 2) Ahora sí: comprueba permiso de exact alarms
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (!am.canScheduleExactAlarms()) {
                                    // Lleva al usuario a ajustes para dar permiso
                                    val settingsIntent =
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data =
                                                Uri.parse("package:${requireContext().packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    requireContext().startActivity(settingsIntent)
                                    return@let   // sin programar hasta que acepte
                                }
                            }

                            // 3) Finalmente programa la alarma
                            am.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                ts.toDate().time,
                                pi   // aquí sí existe
                            )
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(requireContext(), "Error al guardar", Toast.LENGTH_SHORT)
                            .show()
                    }
            }
        }

        dialog.show()
    }

    fun mostrarDialogAfegirEvent() {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_afegir_esdeveniment, null)

        val editTextTitol = dialogView.findViewById<EditText>(R.id.editTextTitol)
        val editTextDescripcio = dialogView.findViewById<EditText>(R.id.editTextDescripcio)
        val textDataLimit = dialogView.findViewById<TextView>(R.id.textViewDataLimit)
        val textFinalitzacio = dialogView.findViewById<TextView>(R.id.textviewFinalitzacio)
        val textRecordatori = dialogView.findViewById<TextView>(R.id.textviewRecordatori)
        val buttonGuardar = dialogView.findViewById<Button>(R.id.buttonGuardarEsdeveniment)


        var dataIniciSeleccionada: Timestamp? = null
        var dataFinalSeleccionada: Timestamp? = null
        var recordatoriSeleccionat: Timestamp? = null
        val calendar = Calendar.getInstance()

        // Selección de data límit
        textDataLimit.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            textDataLimit.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            dataIniciSeleccionada = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.datePicker.minDate = Calendar.getInstance().timeInMillis
            datePicker.show()
        }

        // SELECCIO DATA FINALITZACIÓ
        textFinalitzacio.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            textFinalitzacio.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            dataFinalSeleccionada = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.datePicker.minDate = Calendar.getInstance().timeInMillis
            datePicker.show()
        }

        // Selección de recordatori
        textRecordatori.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            textRecordatori.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            recordatoriSeleccionat = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.show()
        }

        val dialog = builder.setView(dialogView)
            .setTitle("Nova Tasca")
            .create()

        buttonGuardar.setOnClickListener {
            val titol = editTextTitol.text.toString().trim()
            val descripcio = editTextDescripcio.text.toString().trim()
            val uid = FirebaseAuth.getInstance().currentUser?.uid

            if (titol.isEmpty() || dataIniciSeleccionada == null || dataFinalSeleccionada == null) {
                Toast.makeText(
                    requireContext(),
                    "Omple el títol, la data límit i la data de finalització",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (recordatoriSeleccionat != null && recordatoriSeleccionat!! > dataIniciSeleccionada!!) {
                Toast.makeText(
                    requireContext(),
                    "El recordatori no pot ser després de la data límit",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (dataFinalSeleccionada != null && dataFinalSeleccionada!! < dataIniciSeleccionada!!) {
                Toast.makeText(
                    requireContext(),
                    "La data de finalització ha de ser despres de la data d'inici",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            if (uid != null) {
                val db = FirebaseFirestore.getInstance()
                val tasca = hashMapOf(
                    "titol" to titol,
                    "descripció" to descripcio,
                    "data_inici" to dataIniciSeleccionada,
                    "data_fi" to dataFinalSeleccionada,
                    "recordatori" to recordatoriSeleccionat
                )

                db.collection("usuarios")
                    .document(uid)
                    .collection("esdeveniments")
                    .add(tasca)
                    .addOnSuccessListener { docRef ->
                        Toast.makeText(requireContext(), "Esdeveniment guardat", Toast.LENGTH_SHORT)
                            .show()
                        dialog.dismiss()
                        cargareventosytareas(calendarItems, adapter, selectedDate)
                        // Programar alarma si hay recordatori
                        recordatoriSeleccionat?.let { ts ->
                            val am =
                                requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager

                            // 1) Crea el Intent y el PendingIntent (pi)
                            val reminderIntent = Intent(requireContext(), ReminderReceiver::class.java).apply {
                                putExtra("docId", docRef.id)
                                putExtra("type", "esdeveniments")
                                putExtra("titol", titol)
                                putExtra("descripcio", descripcio)
                            }

                            val requestCode = docRef.id.hashCode()
                            val pi = PendingIntent.getBroadcast(
                                requireContext(),
                                requestCode,
                                reminderIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )

                            // 2) Ahora sí: comprueba permiso de exact alarms
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                if (!am.canScheduleExactAlarms()) {
                                    // Lleva al usuario a ajustes para dar permiso
                                    val settingsIntent =
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data =
                                                Uri.parse("package:${requireContext().packageName}")
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                    requireContext().startActivity(settingsIntent)
                                    return@let   // sin programar hasta que acepte
                                }
                            }

                            // 3) Finalmente programa la alarma
                            am.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                ts.toDate().time,
                                pi   // aquí sí existe
                            )
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(requireContext(), "Error al guardar", Toast.LENGTH_SHORT)
                            .show()
                    }
            }
        }

        dialog.show()
    }


    override fun onEdit(item: CalendarItem) {
        if (item.tipo == "Tasca") {
            mostrarDialogEditarTasca(item)
        } else {
            mostrarDialogEditarEvent(item)
        }
    }


    private fun mostrarDialogEditarTasca(item: CalendarItem) {
        val builder = AlertDialog.Builder(requireContext())
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_afegir_tasca, null)
        builder.setView(dialogView)
        val dialog = builder.create()

        // vistas
        val edtTitol = dialogView.findViewById<EditText>(R.id.editTextTitol)
        val edtDesc = dialogView.findViewById<EditText>(R.id.editTextDescripcio)
        val txtDataLimit = dialogView.findViewById<TextView>(R.id.textViewDataLimit)
        val txtRecord = dialogView.findViewById<TextView>(R.id.textviewRecordatori)
        val spinnerEstat = dialogView.findViewById<Spinner>(R.id.spinnerEstat)
        val btnGuardar = dialogView.findViewById<Button>(R.id.buttonGuardarTasca)

        var dataLimitSeleccionada: Timestamp? = null
        var recordatoriSeleccionat: Timestamp? = null
        val calendar = Calendar.getInstance()

        // Spinner
        val estados = listOf("En Proces", "Completada", "Pendiente")
        spinnerEstat.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            estados
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        //cargamos el documento
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val docRef = FirebaseFirestore.getInstance()
            .collection("usuarios")
            .document(uid)
            .collection("tasques")
            .document(item.id)

        docRef.get().addOnSuccessListener { doc ->
            edtTitol.setText(doc.getString("titol"))
            edtDesc.setText(doc.getString("descripció"))


            val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            doc.getTimestamp("data_limit")?.also {
                dataLimitSeleccionada = it
                txtDataLimit.text = fmt.format(it.toDate())
            }
            doc.getTimestamp("recordatori")?.also {
                recordatoriSeleccionat = it
                txtRecord.text = fmt.format(it.toDate())
            }

            val actual = doc.getString("estat") ?: "En Proces"
            spinnerEstat.setSelection(estados.indexOf(actual))
        }

        // 5) DatePicker / TimePicker para volver a seleccionar límites y recordatorio

        // Selección de data límit
        txtDataLimit.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            txtDataLimit.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            dataLimitSeleccionada = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.datePicker.minDate = Calendar.getInstance().timeInMillis
            datePicker.show()
        }

        // Selección de recordatori
        txtRecord.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            txtRecord.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            recordatoriSeleccionat = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.show()
        }


        // Guardar cambios
        btnGuardar.setOnClickListener {
            val newTitol = edtTitol.text.toString().trim()
            val newDesc = edtDesc.text.toString().trim()

            if (newTitol.isEmpty()) {
                Toast.makeText(requireContext(), "El títol no pot estar buit", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            if (recordatoriSeleccionat != null &&
                recordatoriSeleccionat!!.toDate().after(dataLimitSeleccionada!!.toDate())
            ) {
                Toast.makeText(
                    requireContext(),
                    "El recordatori no pot ser després de la data límit",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val updates = hashMapOf<String, Any>(
                "titol" to newTitol,
                "descripció" to edtDesc.text.toString().trim(),
                "estat" to spinnerEstat.selectedItem as String,
                "data_limit" to dataLimitSeleccionada!!
                //"recordatori" to recordatoriSeleccionat!!

            )
            recordatoriSeleccionat?.let { updates["recordatori"] = it }


            docRef.update(updates)
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Tasca actualitzada", Toast.LENGTH_SHORT)
                        .show()
                    dialog.dismiss()
                    cargareventosytareas(calendarItems, adapter, selectedDate)
                    // Primero, cancela la alarma anterior (si existía)
                    val am =
                        requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val oldIntent = Intent(requireContext(), ReminderReceiver::class.java)
                    val oldPi = PendingIntent.getBroadcast(
                        requireContext(),
                        item.id.hashCode(),
                        oldIntent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (oldPi != null) am.cancel(oldPi)

                    // Luego, programa la nueva
                    recordatoriSeleccionat?.let { ts ->
                        val am =
                            requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager

                        // 1) Crea el Intent y el PendingIntent (pi)
                        val reminderIntent = Intent(requireContext(), ReminderReceiver::class.java).apply {
                            putExtra("docId", docRef.id)
                            putExtra("type", "tasques")
                            putExtra("titol", newTitol)    // ahora sí existe
                            putExtra("descripcio", newDesc)
                        }


                        val requestCode = docRef.id.hashCode()
                        val pi = PendingIntent.getBroadcast(
                            requireContext(),
                            requestCode,
                            reminderIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        // 2) Ahora sí: comprueba permiso de exact alarms
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (!am.canScheduleExactAlarms()) {
                                // Lleva al usuario a ajustes para dar permiso
                                val settingsIntent =
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${requireContext().packageName}")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                requireContext().startActivity(settingsIntent)
                                return@let   // sin programar hasta que acepte
                            }
                        }

                        // 3) Finalmente programa la alarma
                        am.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            ts.toDate().time,
                            pi   // aquí sí existe
                        )
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(requireContext(), "Error actualitzant", Toast.LENGTH_SHORT)
                        .show()
                }
        }

        dialog.show()
    }


    private fun mostrarDialogEditarEvent(item: CalendarItem) {
        val builder = AlertDialog.Builder(requireContext())
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_afegir_esdeveniment, null)
        builder.setView(dialogView)
        val dialog = builder.create()

        val edtTitol = dialogView.findViewById<EditText>(R.id.editTextTitol)
        val edtDesc = dialogView.findViewById<EditText>(R.id.editTextDescripcio)
        val txtInici = dialogView.findViewById<TextView>(R.id.textViewDataLimit)
        val txtFinal = dialogView.findViewById<TextView>(R.id.textviewFinalitzacio)
        val txtRecord = dialogView.findViewById<TextView>(R.id.textviewRecordatori)
        val btnGuardar = dialogView.findViewById<Button>(R.id.buttonGuardarEsdeveniment)

        var dataFinalSeleccionada: Timestamp? = null
        var recordatoriSeleccionat: Timestamp? = null
        val calendar = Calendar.getInstance()


        // Cargar doc para obtener los Timestamps originales
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val docRef = FirebaseFirestore.getInstance()
            .collection("usuarios")
            .document(uid)
            .collection("esdeveniments")
            .document(item.id)

        docRef.get().addOnSuccessListener { doc ->
            edtTitol.setText(doc.getString("titol"))
            edtDesc.setText(doc.getString("descripció"))

            val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            doc.getTimestamp("data_fi")?.also {
                dataFinalSeleccionada = it
                txtFinal.text = fmt.format(it.toDate())
            }
            doc.getTimestamp("recordatori")?.also {
                recordatoriSeleccionat = it
                txtRecord.text = fmt.format(it.toDate())
            }
            edtTitol.setText(doc.getString("titol"))
            edtDesc.setText(doc.getString("descripció"))
        }

        // fecha de inicio desabilitada
        txtInici.isClickable = false
        txtInici.isFocusable = false
        txtInici.alpha = 0.6f


        // SELECCIO DATA FINALITZACIÓ
        txtFinal.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            txtFinal.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            dataFinalSeleccionada = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.datePicker.minDate = Calendar.getInstance().timeInMillis
            datePicker.show()
        }

        // Selección de recordatori
        txtRecord.setOnClickListener {
            val datePicker = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val timePicker = TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(year, month, day, hourOfDay, minute)
                            txtRecord.text =
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                                    calendar.time
                                )
                            recordatoriSeleccionat = Timestamp(calendar.time)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    )
                    timePicker.show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            datePicker.show()
        }

        // 3) Guardar cambios
        btnGuardar.setOnClickListener {
            val newTitle = edtTitol.text.toString().trim()
            val newDesc = edtDesc.text.toString().trim()
            if (newTitle.isEmpty()) {
                Toast.makeText(requireContext(), "El títol no pot estar buit", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            if (dataFinalSeleccionada == null) {
                Toast.makeText(
                    requireContext(),
                    "Cal indicar la data de finalització", Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (recordatoriSeleccionat != null &&
                recordatoriSeleccionat!!.toDate().after(dataFinalSeleccionada!!.toDate())
            ) {
                Toast.makeText(
                    requireContext(),
                    "El recordatori no pot ser després de la data de finalització",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val updates = hashMapOf<String, Any>(
                "titol" to newTitle,
                "descripció" to edtDesc.text.toString().trim(),
                //"data_inici" to
                "data_fi" to dataFinalSeleccionada!!,
                "recordatori" to recordatoriSeleccionat!!
            )

            docRef.update(updates)
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Esdeveniment actualitzat", Toast.LENGTH_SHORT)
                        .show()
                    dialog.dismiss()
                    cargareventosytareas(calendarItems, adapter, selectedDate)
                    // Primero, cancela la alarma anterior (si existía)
                    val am =
                        requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val oldIntent = Intent(requireContext(), ReminderReceiver::class.java)
                    val oldPi = PendingIntent.getBroadcast(
                        requireContext(),
                        item.id.hashCode(),
                        oldIntent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (oldPi != null) am.cancel(oldPi)

                    // Luego, programa la nueva
                    recordatoriSeleccionat?.let { ts ->
                        val am =
                            requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager

                        // 1) Crea el Intent y el PendingIntent (pi)
                        val reminderIntent = Intent(requireContext(), ReminderReceiver::class.java).apply {
                            putExtra("docId", docRef.id)
                            putExtra("type", "esdeveniments")  // ← ahora coincide con tu colección de eventos
                            putExtra("titol", newTitle)
                            putExtra("descripcio", newDesc)
                        }

                        val requestCode = docRef.id.hashCode()
                        val pi = PendingIntent.getBroadcast(
                            requireContext(),
                            requestCode,
                            reminderIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        // 2) Ahora sí: comprueba permiso de exact alarms
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            if (!am.canScheduleExactAlarms()) {
                                // Lleva al usuario a ajustes para dar permiso
                                val settingsIntent =
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${requireContext().packageName}")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                requireContext().startActivity(settingsIntent)
                                return@let   // sin programar hasta que acepte
                            }
                        }

                        // 3) Finalmente programa la alarma
                        am.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            ts.toDate().time,
                            pi   // aquí sí existe
                        )
                    }

                }

                .addOnFailureListener {
                    Toast.makeText(requireContext(), "Error actualitzant", Toast.LENGTH_SHORT)
                        .show()
                }
        }

        dialog.show()
    }


    override fun onDelete(item: CalendarItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Borrar ${item.tipo}")
            .setMessage("¿Seguro que deseas borrar “${item.title}”?")
            .setPositiveButton("Sí") { _, _ ->
                val path = if (item.tipo == "Tasca") "tasques" else "esdeveniments"
                FirebaseFirestore.getInstance()
                    .collection("usuarios")
                    .document(FirebaseAuth.getInstance().uid!!)
                    .collection(path)
                    .document(item.id)
                    .delete()
                    .addOnSuccessListener {
                        // Actualiza la lista en memoria y notifica al adapter
                        val idx = calendarItems.indexOf(item)
                        calendarItems.removeAt(idx)
                        recyclerView.adapter?.notifyItemRemoved(idx)
                    }
            }
            .setNegativeButton("No", null)
            .show()
    }
}