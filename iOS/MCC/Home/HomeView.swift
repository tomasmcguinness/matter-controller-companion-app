import SwiftUI

struct HomeView: View {

    @State private var model = ControllersModel()
    @State private var isShowingAddController = false

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
            }
            .onDelete(perform: model.remove)
        }
        .navigationDestination(for: Controller.self) { controller in
            ControllerDeviceListView(controller: controller)
        }
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
