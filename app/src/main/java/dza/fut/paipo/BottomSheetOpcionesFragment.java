package dza.fut.paipo;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

public class BottomSheetOpcionesFragment extends BottomSheetDialogFragment {

    private static final String ARG_CANAL = "canal";
    private Canal canal;

    public static BottomSheetOpcionesFragment newInstance(Canal canal) {
        BottomSheetOpcionesFragment fragment = new BottomSheetOpcionesFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_CANAL, canal); // Asegúrate de que Canal implemente Serializable o usa Parcelable
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_opciones_canal, container, false);

        if (getArguments() != null) {
            canal = (Canal) getArguments().getSerializable(ARG_CANAL);
        }

        if (canal == null) {
            dismiss();
            return view;
        }

        // Header
        ImageView iconoCanal = view.findViewById(R.id.iconoCanal);
        TextView tituloCanal = view.findViewById(R.id.tituloCanal);
        Glide.with(requireContext())
                .load(canal.image)
                .circleCrop()
                .into(iconoCanal);
        tituloCanal.setText(canal.title);

        // Lista de opciones
        RecyclerView recyclerOpciones = view.findViewById(R.id.recyclerOpciones);
        recyclerOpciones.setLayoutManager(new LinearLayoutManager(getContext()));

        if (canal.options != null && !canal.options.isEmpty()) {
            OpcionesAdapter adapter = new OpcionesAdapter(canal.options, opcion -> {
                if (opcion.url == null || opcion.url.isEmpty()) {
                    Toast.makeText(getContext(), "URL no disponible", Toast.LENGTH_SHORT).show();
                    return;
                }
                lanzarReproductor(opcion);
                dismiss(); // cerrar el bottom sheet al seleccionar
            });
            recyclerOpciones.setAdapter(adapter);
        } else {
            Toast.makeText(getContext(), "Sin opciones", Toast.LENGTH_SHORT).show();
            dismiss();
        }

        return view;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void lanzarReproductor(Canal.OpcionCanal elegida) {
        Intent intent = new Intent(getActivity(), PlayerActivity.class);
        String urlFinal = normalizarUrlCanal(getContext(), elegida.url);
        // v3.2.66: van TODAS las opciones (ya normalizadas) + el indice elegido,
        // para que el reproductor pinte sus chips de servidores y se pueda
        // cambiar de opcion sin salir (seleccion manual, nunca automatica).
        if (canal != null && canal.options != null && !canal.options.isEmpty()) {
            int n = canal.options.size();
            String[] etiquetas = new String[n];
            String[] urls = new String[n];
            int indice = 0;
            for (int i = 0; i < n; i++) {
                Canal.OpcionCanal o = canal.options.get(i);
                etiquetas[i] = o.label == null ? "" : o.label;
                urls[i] = normalizarUrlCanal(getContext(), o.url);
                if (o == elegida) indice = i;
            }
            intent.putExtra("opciones_labels", etiquetas);
            intent.putExtra("opciones_urls", urls);
            intent.putExtra("opcion_indice", indice);
            urlFinal = urls[indice];
        }
        if (canal != null) intent.putExtra("canal_titulo", canal.title);
        intent.putExtra("url_iframe_inicial", urlFinal);
        startActivity(intent);

        // 👇 Cerrar la actividad de canales para que el reproductor sea la única pantalla activa
        if (getActivity() != null) {

        }
    }

    /**
     * Misma normalizacion que en CanalAdapter: las rutas del listado de canales
     * son relativas ("/p/...") y ese envoltorio ya no existe en el servidor
     * (404). Se usa directamente la URL interna "?r=" que es absoluta.
     */
    static String normalizarUrlCanal(android.content.Context cx, String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith("dramalive://")) return u; // v3.2.5: esquema propio, sin normalizar
        if (u.startsWith("http")) {
            // enlace cifrado de Drama
            String drama = EnlaceDrama.desenrollar(u, cx == null ? null : FuenteCanales.dramaKey(cx));
            return drama != null ? drama : u;
        }
        int i = u.indexOf("?r=");
        if (i >= 0) {
            String interna = u.substring(i + 3).trim();
            if (interna.startsWith("http")) return interna;
        }
        return "https://belkaperu.github.io" + u;
    }

    private String extraerToken(String url) {
        if (url.contains("?r=")) return url.substring(url.indexOf("?r=") + 3);
        return url;
    }

    // Adaptador para las opciones
    private static class OpcionesAdapter extends RecyclerView.Adapter<OpcionesAdapter.ViewHolder> {

        private final List<Canal.OpcionCanal> opciones;
        private final OnOpcionClickListener listener;

        public interface OnOpcionClickListener {
            void onOpcionClick(Canal.OpcionCanal opcion);
        }

        public OpcionesAdapter(List<Canal.OpcionCanal> opciones, OnOpcionClickListener listener) {
            this.opciones = opciones;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_opcion_bottom_sheet, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Canal.OpcionCanal opcion = opciones.get(position);
            holder.textLabel.setText(opcion.label);
            holder.itemView.setOnClickListener(v -> listener.onOpcionClick(opcion));
        }

        @Override
        public int getItemCount() {
            return opciones.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView textLabel;
            ViewHolder(View itemView) {
                super(itemView);
                textLabel = itemView.findViewById(R.id.textOpcionLabel);
            }
        }
    }
}