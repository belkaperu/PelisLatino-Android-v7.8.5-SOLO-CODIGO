package dza.fut.paipo;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Catálogo de películas y series con búsqueda y paginación incremental. */
public class PelisFragment extends Fragment {

    private static final String TAG = "PelisFragment";
    private static final long BUSQUEDA_DEBOUNCE_MS = 320L;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());

    private EditText editBuscar;
    private ImageButton btnBuscar, btnLimpiar;
    private TextView btnTodos, btnPeliculas, btnSeries;
    private TextView btnFiltroGenero, btnFiltroPais, btnFiltroAnio, btnFavoritos, btnLimpiarFiltros;
    private TextView btnToggleFiltros;
    private View panelFiltros;
    private android.widget.LinearLayout tiraAnios;
    private android.widget.LinearLayout listaFiltrosInline;
    private View scrollListaFiltros;
    private String listaFiltrosAbierta; // null | "Genero" | "Pais" | "Anio"
    private boolean filtrosVisibles = false;
    private TextView txtResultados, txtVacio;
    private RecyclerView recycler;
    private ProgressBar progress, progressPie;

    private PelisAdapter adapter;
    private GridLayoutManager layoutManager;
    private TextWatcher searchWatcher;
    private Runnable pendingSearch;

    private List<PelisApi.OpcionFiltro> opcionesGenero = Collections.emptyList();
    private List<PelisApi.OpcionFiltro> opcionesPais = Collections.emptyList();
    private List<PelisApi.OpcionFiltro> opcionesAnio = Collections.emptyList();
    private String tipoActual = PelisItem.TIPO_TODOS;
    private String busqueda = "";
    private String nombreGenero = "";
    private String nombrePais = "";
    private int generoSeleccionado = 0;
    private int paisSeleccionado = 0;
    private int anioSeleccionado = 0;
    private int pagina = 1;
    private int totalResultados = 0;
    private long requestId = 0;
    private boolean cargando = false;
    private boolean hayMas = true;
    private boolean errorActual = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_pelis, container, false);

        editBuscar = view.findViewById(R.id.editBuscarPelis);
        btnBuscar = view.findViewById(R.id.btnBuscarPelis);
        btnLimpiar = view.findViewById(R.id.btnLimpiarBusquedaPelis);
        btnTodos = view.findViewById(R.id.btnTodosPelis);
        btnPeliculas = view.findViewById(R.id.btnPeliculasPelis);
        btnSeries = view.findViewById(R.id.btnSeriesPelis);
        btnFiltroGenero = view.findViewById(R.id.btnFiltroGeneroPelis);
        btnFiltroPais = view.findViewById(R.id.btnFiltroPaisPelis);
        btnFiltroAnio = view.findViewById(R.id.btnFiltroAnioPelis);
        btnFavoritos = view.findViewById(R.id.btnFavoritosPelis);
        btnLimpiarFiltros = view.findViewById(R.id.btnLimpiarFiltrosPelis);
        panelFiltros = view.findViewById(R.id.panelFiltrosPelis);
        tiraAnios = view.findViewById(R.id.tiraAniosPelis);
        listaFiltrosInline = view.findViewById(R.id.listaFiltrosInline);
        scrollListaFiltros = view.findViewById(R.id.scrollListaFiltros);
        // v3.2.38: las opciones del ultimo catalogo visto salen de prefs AL INSTANTE
        // — Genero/Pais dejan de depender de que la red conteste para poder elegirse.
        android.content.SharedPreferences up =
                requireContext().getSharedPreferences("pelis_ui", 0);
        if (opcionesGenero.isEmpty())
            opcionesGenero = PelisApi.opcionesDesdeLineas(up.getString("gen_json", ""));
        if (opcionesPais.isEmpty())
            opcionesPais = PelisApi.opcionesDesdeLineas(up.getString("pais_json", ""));
        if (opcionesAnio.isEmpty())
            opcionesAnio = PelisApi.opcionesDesdeLineas(up.getString("anio_json", ""));
        refrescarListaFiltros();
        // v3.2.39: los favoritos entran a la API antes del primer cargar() y el
        // ultimo tipo elegido (p.ej. Favoritos) se restaura — la seleccion sobrevive.
        PelisApi.FAVORITOS.clear();
        PelisApi.FAVORITOS.addAll(Favoritos.deserializar(up.getString("favoritos", "")));
        String tipoGuardado = up.getString("tipo_actual", "");
        if (!tipoGuardado.isEmpty()) tipoActual = tipoGuardado;
        btnToggleFiltros = view.findViewById(R.id.btnToggleFiltrosPelis);
        txtResultados = view.findViewById(R.id.txtResultadosPelis);
        recycler = view.findViewById(R.id.recyclerPelis);
        progress = view.findViewById(R.id.progressPelis);
        progressPie = view.findViewById(R.id.progressPelisPie);
        txtVacio = view.findViewById(R.id.txtVacioPelis);

        layoutManager = new GridLayoutManager(requireContext(), columnas());
        recycler.setLayoutManager(layoutManager);
        adapter = new PelisAdapter(requireContext(), this::abrirDetalle);
        adapter.setOnFavoritoClick(item -> {
            boolean ahora = Favoritos.alternar(PelisApi.FAVORITOS, item.claveFavorito());
            requireContext().getSharedPreferences("pelis_ui", 0).edit()
                    .putString("favoritos", Favoritos.serializar(PelisApi.FAVORITOS)).apply();
            android.util.Log.i("BLABONGO", "favorito " + (ahora ? "anadido" : "quitado")
                    + ": " + item.titulo);
            if (PelisItem.TIPO_FAVORITOS.equals(tipoActual)) {
                pagina = 1;
                cargar(true); // en la vista de favoritos, quitar = sale de la lista
            } else if (adapter != null) {
                adapter.notifyDataSetChanged(); // el corazon se repinta
            }
        });
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || cargando || !hayMas || adapter.total() == 0) return;
                int ultimo = layoutManager.findLastVisibleItemPosition();
                if (ultimo >= adapter.total() - 6) {
                    pagina++;
                    cargar(false);
                }
            }
        });

        btnTodos.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_TODOS));
        btnPeliculas.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_PELICULA));
        btnSeries.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_SERIE));
        btnFiltroGenero.setOnClickListener(v -> alternarListaFiltros("Genero"));
        btnFiltroPais.setOnClickListener(v -> alternarListaFiltros("Pais"));
        btnFiltroAnio.setOnClickListener(v -> alternarListaFiltros("Anio"));
        btnFavoritos.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_FAVORITOS));
        btnLimpiarFiltros.setOnClickListener(v -> limpiarFiltros());
        // v3.2.35: búsqueda y filtros nacen ESCONDIDOS para dejar ver las pelis;
        // un solo chip del encabezado los muestra y los vuelve a esconder.
        btnToggleFiltros.setOnClickListener(v -> {
            filtrosVisibles = !filtrosVisibles;
            panelFiltros.setVisibility(filtrosVisibles ? View.VISIBLE : View.GONE);
            if (!filtrosVisibles) cerrarListaFiltros(); // v3.2.38
            requireContext().getSharedPreferences("pelis_ui", 0).edit()
                    .putBoolean("filtros_visibles", filtrosVisibles).apply();
            pintarToggleFiltros();
            if (filtrosVisibles) editBuscar.requestFocus();
        });
        // v3.2.36: el abierto/cerrado sobrevive a la reconstruccion de la vista
        // (girar la pantalla, cambiar de pestaña y volver) — si no, el panel
        // «se cerraba solo» y eso era lo que parecia un fallo del click.
        filtrosVisibles = requireContext().getSharedPreferences("pelis_ui", 0)
                .getBoolean("filtros_visibles", false);
        panelFiltros.setVisibility(filtrosVisibles ? View.VISIBLE : View.GONE);
        pintarToggleFiltros();
        // v3.2.37: la tira se pinta AUN SIN RED — si el catalogo no ha bajado,
        // salen los anios de reserva y el toque aplica el filtro igual.
        pintarTiraAnios();
        btnBuscar.setOnClickListener(v -> buscar(editBuscar.getText().toString()));
        btnLimpiar.setOnClickListener(v -> editBuscar.setText(""));

        editBuscar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                buscar(editBuscar.getText().toString());
                return true;
            }
            return false;
        });

        searchWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable editable) {
                String texto = editable == null ? "" : editable.toString().trim();
                btnLimpiar.setVisibility(texto.isEmpty() ? View.GONE : View.VISIBLE);
                programarBusqueda(texto);
            }
        };
        editBuscar.addTextChangedListener(searchWatcher);

        pintarFiltros();
        actualizarCabecera();
        cargar(true);
        cargarFiltros();
        return view;
    }

    private int columnas() {
        Resources resources = getResources();
        float anchoDp = resources.getConfiguration().screenWidthDp;
        if (anchoDp <= 0) {
            DisplayMetrics dm = resources.getDisplayMetrics();
            anchoDp = dm.widthPixels / dm.density;
        }
        // Pósteres legibles en móvil; más columnas al crecer la ventana/tablet.
        int cols = (int) ((anchoDp + 12f) / 160f);
        return Math.max(2, Math.min(6, cols));
    }

    private void cambiarTipo(String tipo) {
        if (tipo.equals(tipoActual)) return;
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
            busqueda = editBuscar.getText().toString().trim();
        }
        tipoActual = tipo;
        if (isAdded()) {
            requireContext().getSharedPreferences("pelis_ui", 0).edit()
                    .putString("tipo_actual", tipo).apply();
        }
        pintarFiltros();
        recycler.scrollToPosition(0);
        cargar(true);
    }

    private void pintarFiltros() {
        pintarFiltro(btnTodos, PelisItem.TIPO_TODOS.equals(tipoActual));
        pintarFiltro(btnPeliculas, PelisItem.TIPO_PELICULA.equals(tipoActual));
        pintarFiltro(btnSeries, PelisItem.TIPO_SERIE.equals(tipoActual));
        pintarFiltro(btnFavoritos, PelisItem.TIPO_FAVORITOS.equals(tipoActual));
        pintarSelectores();
    }

    /**
     * Estado de un chip de filtro.
     *
     * Antes se cambiaba a mano el fondo y el color de texto; ahora solo se marca
     * el estado `selected` y el selector (@drawable/bg_pelis_filter) junto con la
     * lista de estados (@color/pelis_chip_text) pintan el chip. Así el chip tiene
     * el mismo ripple/resaltado que el resto de controles y no hay dos fuentes
     * de verdad para el mismo estado.
     */
    private void pintarFiltro(TextView button, boolean seleccionado) {
        if (button == null) return;
        button.setSelected(seleccionado);
        button.setTypeface(
                android.graphics.Typeface.create("sans-serif-medium",
                        seleccionado ? android.graphics.Typeface.BOLD
                                     : android.graphics.Typeface.NORMAL));
    }

    private void pintarSelectores() {
        if (btnFiltroGenero == null || btnFiltroPais == null || btnFiltroAnio == null) return;
        String genero = generoSeleccionado == 0 ? "Género"
                : "Género: " + (nombreGenero.isEmpty() ? "Seleccionado" : nombreGenero);
        String pais = paisSeleccionado == 0 ? "País"
                : "País: " + (nombrePais.isEmpty() ? "Seleccionado" : nombrePais);
        String anio = anioSeleccionado == 0 ? "Año" : "Año: " + anioSeleccionado;
        btnFiltroGenero.setText(genero + "  ▾");
        btnFiltroPais.setText(pais + "  ▾");
        btnFiltroAnio.setText(anio + "  ▾");
        pintarFiltro(btnFiltroGenero, generoSeleccionado > 0);
        pintarFiltro(btnFiltroPais, paisSeleccionado > 0);
        pintarFiltro(btnFiltroAnio, anioSeleccionado > 0);
        if (btnLimpiarFiltros != null) {
            btnLimpiarFiltros.setVisibility(generoSeleccionado > 0 || paisSeleccionado > 0
                    || anioSeleccionado > 0 ? View.VISIBLE : View.GONE);
        }
        pintarToggleFiltros();
        pintarTiraAnios();
    }

    /** v3.2.37: años como chips directos del panel. Dieciseis maximо, los más
     *  recientes primero; el chip final abre el dialogo con la lista completa. */
    private void pintarTiraAnios() {
        if (tiraAnios == null) return;
        tiraAnios.removeAllViews();
        List<PelisApi.OpcionFiltro> anos =
                (opcionesAnio != null && !opcionesAnio.isEmpty())
                        ? opcionesAnio : PelisApi.aniosPorDefecto();
        final int tope = Math.min(anos.size(), 16);
        int dp6 = (int) (6 * getResources().getDisplayMetrics().density + 0.5f);
        for (int i = 0; i < tope; i++) {
            final PelisApi.OpcionFiltro op = anos.get(i);
            TextView ch = new TextView(new android.view.ContextThemeWrapper(
                    requireContext(), R.style.Widget_Blabongo_Chip));
            ch.setText(op.nombre); // v3.2.39: sin conteos — se pedio prolijidad
            ch.setSelected(op.id == anioSeleccionado);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp6);
            ch.setLayoutParams(lp);
            ch.setOnClickListener(v -> {
                anioSeleccionado = (op.id == anioSeleccionado) ? 0 : op.id;
                android.util.Log.i("BLABONGO", "tira anio -> " + anioSeleccionado);
                actualizarFiltrosYRecargar();
            });
            tiraAnios.addView(ch);
        }
        TextView mas = new TextView(new android.view.ContextThemeWrapper(
                requireContext(), R.style.Widget_Blabongo_Chip));
        mas.setText("Todos los años  ▾");
        android.widget.LinearLayout.LayoutParams lpMas =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lpMas.setMarginStart(dp6);
        mas.setLayoutParams(lpMas);
        mas.setOnClickListener(v -> alternarListaFiltros("Anio"));
        tiraAnios.addView(mas);
    }

    /** v3.2.35: el chip plegable avisa si hay filtros activos aunque el panel esté cerrado. */
    private void pintarToggleFiltros() {
        if (btnToggleFiltros == null) return;
        if (filtrosVisibles) {
            btnToggleFiltros.setText("Ocultar  ▴");
            btnToggleFiltros.setSelected(false);
            return;
        }
        boolean activos = generoSeleccionado > 0 || paisSeleccionado > 0
                || anioSeleccionado > 0 || !busqueda.isEmpty();
        btnToggleFiltros.setText(activos ? "Filtros activos  ▾" : "Buscar y filtrar  ▾");
        btnToggleFiltros.setSelected(activos);
    }

    private void cargarFiltros() {
        PelisApi.filtrosCatalogo(new PelisApi.Callback<PelisApi.FiltrosCatalogo>() {
            @Override
            public void onOk(PelisApi.FiltrosCatalogo data) {
                if (!isAdded() || recycler == null || data == null) return;
                if (data.generos != null && !data.generos.isEmpty()) {
                    opcionesGenero = data.generos;
                    guardarOpcionesPrefs("gen_json", data.generos);
                }
                if (data.paises != null && !data.paises.isEmpty()) {
                    opcionesPais = data.paises;
                    guardarOpcionesPrefs("pais_json", data.paises);
                }
                if (data.anos != null && !data.anos.isEmpty()) {
                    opcionesAnio = data.anos;
                    guardarOpcionesPrefs("anio_json", data.anos);
                }
                refrescarListaFiltros();
                nombreGenero = nombreOpcion(opcionesGenero, generoSeleccionado);
                nombrePais = nombreVisiblePais(nombreOpcion(opcionesPais, paisSeleccionado));
                pintarSelectores();
                actualizarCabecera();
            }

            @Override
            public void onError(String mensaje) {
                Log.w(TAG, "No se pudieron cargar géneros y países: " + mensaje);
            }
        });
    }

    private String nombreOpcion(List<PelisApi.OpcionFiltro> opciones, int id) {
        if (id <= 0 || opciones == null) return "";
        for (PelisApi.OpcionFiltro opcion : opciones) {
            if (opcion.id == id) return opcion.nombre;
        }
        return "";
    }

    private String nombreVisiblePais(String nombre) {
        return "United States of America".equals(nombre) ? "United States" : nombre;
    }

    private interface OnFiltroElegido {
        void elegir(@Nullable PelisApi.OpcionFiltro opcion);
    }

    /** v3.2.38: CERO dialogos — la ROM del usuario los cierra solos (medido con
     *  Ano y replicado con Genero). Las opciones se despliegan dentro del panel:
     *  un toque elige, otro toque al mismo chip pliega. Sin ventana, no hay
     *  forma de que "se cierre". */
    private void alternarListaFiltros(String titulo) {
        if (listaFiltrosInline == null || scrollListaFiltros == null) return;
        if (titulo.equals(listaFiltrosAbierta)) {
            cerrarListaFiltros();
            return;
        }
        listaFiltrosAbierta = titulo;
        scrollListaFiltros.setVisibility(View.VISIBLE);
        android.util.Log.i("BLABONGO", "lista inline abierta: " + titulo);
        refrescarListaFiltros();
    }

    private void cerrarListaFiltros() {
        listaFiltrosAbierta = null;
        if (scrollListaFiltros != null) scrollListaFiltros.setVisibility(View.GONE);
    }

    /** Se llama tambien cuando el catalogo termina de bajar (onOk) para repintar
     *  la lista abierta con los conteos reales. */
    private void refrescarListaFiltros() {
        if (listaFiltrosInline == null || listaFiltrosAbierta == null) return;
        final String t = listaFiltrosAbierta;
        java.util.List<PelisApi.OpcionFiltro> ops;
        int sel;
        String todos;
        final OnFiltroElegido cb;
        if ("Genero".equals(t)) {
            ops = opcionesGenero;
            sel = generoSeleccionado;
            todos = "Todos los generos";
            cb = opcion -> {
                int nuevoId = opcion == null ? 0 : opcion.id;
                nombreGenero = opcion == null ? "" : opcion.nombre;
                if (nuevoId == generoSeleccionado) return;
                generoSeleccionado = nuevoId;
                actualizarFiltrosYRecargar();
            };
        } else if ("Pais".equals(t)) {
            ops = opcionesPais;
            sel = paisSeleccionado;
            todos = "Todos los paises";
            cb = opcion -> {
                int nuevoId = opcion == null ? 0 : opcion.id;
                nombrePais = opcion == null ? "" : nombreVisiblePais(opcion.nombre);
                if (nuevoId == paisSeleccionado) return;
                paisSeleccionado = nuevoId;
                actualizarFiltrosYRecargar();
            };
        } else { // "Anio"
            ops = (opcionesAnio != null && !opcionesAnio.isEmpty())
                    ? opcionesAnio : PelisApi.aniosPorDefecto();
            sel = anioSeleccionado;
            todos = "Todos los anios";
            cb = opcion -> {
                int nuevoId = opcion == null ? 0 : opcion.id;
                if (nuevoId == anioSeleccionado) return;
                anioSeleccionado = nuevoId;
                actualizarFiltrosYRecargar();
            };
        }
        listaFiltrosInline.removeAllViews();
        if (ops == null || ops.isEmpty()) {
            addFilaOpcion("El catalogo a\u00fan no ha bajado — toca para reintentar",
                    false, v -> {
                        cerrarListaFiltros();
                        cargarFiltros();
                    });
            return;
        }
        addFilaOpcion(todos, sel == 0, v -> {
            cerrarListaFiltros();
            cb.elegir(null);
        });
        for (final PelisApi.OpcionFiltro op : ops) {
            String nombre = "Pais".equals(t) ? nombreVisiblePais(op.nombre) : op.nombre;
            addFilaOpcion(nombre, op.id == sel, v -> {
                cerrarListaFiltros();
                cb.elegir(op);
            });
        }
    }

    private void addFilaOpcion(String texto, boolean seleccionado,
                                android.view.View.OnClickListener clic) {
        TextView fila = new TextView(requireContext());
        fila.setTextSize(14f);
        fila.setText(texto);
        fila.setTextColor(getResources().getColor(R.color.pelis_text));
        fila.setBackgroundResource(R.drawable.bg_pelis_filter);
        fila.setSelected(seleccionado);
        fila.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                seleccionado ? android.graphics.Typeface.BOLD
                             : android.graphics.Typeface.NORMAL));
        fila.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        int dp = (int) (getResources().getDisplayMetrics().density + 0.5f);
        fila.setPadding(12 * dp, 9 * dp, 12 * dp, 9 * dp);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        if (listaFiltrosInline.getChildCount() > 0) lp.topMargin = 5 * dp;
        fila.setLayoutParams(lp);
        fila.setOnClickListener(clic);
        listaFiltrosInline.addView(fila);
    }

    private void guardarOpcionesPrefs(String clave, java.util.List<PelisApi.OpcionFiltro> ops) {
        if (!isAdded()) return;
        requireContext().getSharedPreferences("pelis_ui", 0).edit()
                .putString(clave, PelisApi.lineasDeOpciones(ops)).apply();
    }

    private void actualizarFiltrosYRecargar() {
        pintarSelectores();
        pagina = 1;
        if (recycler != null) recycler.scrollToPosition(0);
        cargar(true);
    }

    private void limpiarFiltros() {
        if (generoSeleccionado == 0 && paisSeleccionado == 0 && anioSeleccionado == 0) return;
        generoSeleccionado = 0;
        paisSeleccionado = 0;
        anioSeleccionado = 0;
        nombreGenero = "";
        nombrePais = "";
        actualizarFiltrosYRecargar();
    }

    private void programarBusqueda(String texto) {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        if (texto.equals(busqueda)) return;
        pendingSearch = () -> {
            pendingSearch = null;
            if (!isAdded() || texto.equals(busqueda)) return;
            busqueda = texto;
            pagina = 1;
            if (recycler != null) recycler.scrollToPosition(0);
            cargar(true);
        };
        searchHandler.postDelayed(pendingSearch, BUSQUEDA_DEBOUNCE_MS);
    }

    private void buscar(String texto) {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        String nuevaBusqueda = texto == null ? "" : texto.trim();
        ocultarTeclado();
        if (nuevaBusqueda.equals(busqueda)) {
            if (recycler != null) recycler.scrollToPosition(0);
            return;
        }
        busqueda = nuevaBusqueda;
        pagina = 1;
        if (recycler != null) recycler.scrollToPosition(0);
        cargar(true);
    }

    private void ocultarTeclado() {
        View foco = getActivity() != null ? getActivity().getCurrentFocus() : null;
        if (foco == null || getContext() == null) return;
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(foco.getWindowToken(), 0);
    }

    private void actualizarCabecera() {
        if (txtResultados == null) return; // v3.2.39: el conteo ya no se muestra

        if (!busqueda.isEmpty()) {
            txtResultados.setText("Resultados para “" + busqueda + "”");
        } else if (generoSeleccionado > 0 || paisSeleccionado > 0) {
            StringBuilder titulo = new StringBuilder();
            if (generoSeleccionado > 0) {
                titulo.append("Género: ").append(nombreGenero.isEmpty() ? "Seleccionado" : nombreGenero);
            }
            if (paisSeleccionado > 0) {
                if (titulo.length() > 0) titulo.append(" · ");
                titulo.append("País: ").append(nombrePais.isEmpty() ? "Seleccionado" : nombrePais);
            }
            txtResultados.setText(titulo.toString());
        } else if (PelisItem.TIPO_PELICULA.equals(tipoActual)) {
            txtResultados.setText("Películas recientes");
        } else if (PelisItem.TIPO_SERIE.equals(tipoActual)) {
            txtResultados.setText("Series recientes");
        } else if (PelisItem.TIPO_FAVORITOS.equals(tipoActual)) {
            txtResultados.setText("★ Tus favoritos");
        } else {
            txtResultados.setText("Explorar catálogo");
        }

    }

    /** @param reiniciar true = primera página (limpia la lista). */
    private void cargar(boolean reiniciar) {
        if (!isAdded() || adapter == null) return;

        if (reiniciar) {
            pagina = 1;
            hayMas = true;
            totalResultados = 0;
            adapter.limpiar();
            txtVacio.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        } else {
            progressPie.setVisibility(View.VISIBLE);
        }

        errorActual = false;
        cargando = true;
        actualizarCabecera();

        final int paginaSolicitada = pagina;
        final long solicitud = ++requestId;
        PelisApi.catalogoPagina(tipoActual, paginaSolicitada, busqueda,
                generoSeleccionado, paisSeleccionado, anioSeleccionado,
                new PelisApi.Callback<PelisApi.PaginaCatalogo>() {
                    @Override
                    public void onOk(PelisApi.PaginaCatalogo data) {
                        if (!isAdded() || recycler == null || solicitud != requestId) return;
                        cargando = false;
                        progress.setVisibility(View.GONE);
                        progressPie.setVisibility(View.GONE);
                        errorActual = false;

                        if (data == null || data.items == null) {
                            mostrarError("No se pudo leer el catálogo. Toca para reintentar.");
                            return;
                        }

                        totalResultados = data.total;
                        if (data.items.isEmpty()) {
                            hayMas = false;
                            if (adapter.total() == 0) {
                                if (!busqueda.isEmpty()) {
                                    txtVacio.setText("Sin resultados para “" + busqueda
                                            + "”.\nPrueba con otro título.");
                                } else if (generoSeleccionado > 0 || paisSeleccionado > 0) {
                                    txtVacio.setText("No encontramos títulos con esos filtros.\nPrueba otra combinación.");
                                } else {
                                    txtVacio.setText("No hay contenido disponible por ahora.");
                                }
                                txtVacio.setVisibility(View.VISIBLE);
                            }
                            actualizarCabecera();
                            return;
                        }

                        adapter.agregar(data.items);
                        hayMas = paginaSolicitada * PelisApi.POR_PAGINA < totalResultados;
                        actualizarCabecera();
                    }

                    @Override
                    public void onError(String mensaje) {
                        if (!isAdded() || recycler == null || solicitud != requestId) return;
                        cargando = false;
                        progress.setVisibility(View.GONE);
                        progressPie.setVisibility(View.GONE);
                        Log.e(TAG, "Error: " + mensaje);

                        if (adapter.total() == 0) {
                            mostrarError("No se pudo cargar el catálogo.\nToca aquí para reintentar.");
                        } else {
                            Toast.makeText(requireContext(), "No se pudieron cargar más títulos.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void mostrarError(String mensaje) {
        errorActual = true;
        txtVacio.setText(mensaje);
        txtVacio.setVisibility(View.VISIBLE);
        txtVacio.setOnClickListener(v -> {
            if (errorActual) cargar(true);
        });
        actualizarCabecera();
    }

    private void abrirDetalle(PelisItem item) {
        Intent intent = new Intent(getContext(), PelisDetailActivity.class);
        intent.putExtra(PelisDetailActivity.EXTRA_ITEM, item);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        requestId++;
        if (editBuscar != null && searchWatcher != null) {
            editBuscar.removeTextChangedListener(searchWatcher);
        }
        if (recycler != null) recycler.clearOnScrollListeners();

        editBuscar = null;
        btnBuscar = null;
        btnLimpiar = null;
        btnTodos = null;
        btnPeliculas = null;
        btnSeries = null;
        btnFiltroGenero = null;
        btnFiltroPais = null;
        btnLimpiarFiltros = null;
        txtResultados = null;
        txtVacio = null;
        recycler = null;
        progress = null;
        progressPie = null;
        adapter = null;
        layoutManager = null;
        searchWatcher = null;
        super.onDestroyView();
    }
}
