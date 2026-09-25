import SwiftUI

struct HomeView: View {

    @State private var model = ControllersModel()
    @State private var isShowingAddController = false
    @State private var pendingDeletion: IndexSet?
    @State private var editingController: Controller?

    var body: some View {
        NavigationStack {
            Group {
                if model.controllers.isEmpty {
                    empty
                } else {
                    list
                }
            }
            .navigationTitle("Home")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        isShowingAddController = true
                    } label: {
                        Label("Add Controller", systemImage: "plus")
                    }
                }
            }
            .sheet(isPresented: $isShowingAddController) {
                AddControllerView {
                    model.load()
                }
            }
            .sheet(item: $editingController) { controller in
                AddControllerView(editing: controller) {
                    model.load()
                }
            }
        }
        .task {
            model.load()
        }
    }

    private var empty: some View {
        ContentUnavailableView {
            Label("No Controllers", systemImage: "server.rack")
        } description: {
            Text("Tap + to add a Matter controller by name and address.")
        } actions: {
            Button("Add Controller") { isShowingAddController = true }
                .buttonStyle(.borderedProminent)
        }
    }

    private var list: some View {
        List {
            ForEach(model.controllers) { controller in
                NavigationLink(value: controller) {
                    ControllerRow(controller: controller)
                }
                .swipeActions(edge: .leading) {
                    Button("Edit", systemImage: "pencil") {
                        editingController = controller
                    }
                    .tint(.blue)
                }
                .contextMenu {
                    Button("Edit", systemImage: "pencil") {
                        editingController = controller
                    }
                }
            }
            .onDelete { offsets in
                pendingDeletion = offsets
            }
        }
        .confirmationDialog(
            deletionTitle,
            isPresented: Binding(
                get: { pendingDeletion != nil },
                set: { if !$0 { pendingDeletion = nil } }
            ),
            titleVisibility: .visible,
            presenting: pendingDeletion
        ) { offsets in
            Button("Delete", role: .destructive) {
                model.remove(at: offsets)
                pendingDeletion = nil
            }
            Button("Cancel", role: .cancel) {
                pendingDeletion = nil
            }
        } message: { _ in
            Text("This removes the controller from this app. Devices on the controller are not affected.")
        }
        .navigationDestination(for: Controller.self) { controller in
            ControllerDeviceListView(controller: controller)
        }
    }

    private var deletionTitle: String {
        guard let offsets = pendingDeletion else { return "Delete Controller?" }
        if offsets.count == 1, let index = offsets.first, model.controllers.indices.contains(index) {
            return "Delete \"\(model.controllers[index].name)\"?"
        }
        return "Delete \(offsets.count) Controllers?"
    }
}

struct ControllerRow: View {

    let controller: Controller

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(controller.name)
                .font(.body)

            Text(controller.url.absoluteString)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

#Preview {
    HomeView()
}
